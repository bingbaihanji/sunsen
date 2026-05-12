package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.PluginLoadException;
import com.bingbaihanji.sunsen.api.PluginState;

import java.io.IOException;
import java.nio.file.*;
import java.util.concurrent.RejectedExecutionException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 插件目录文件监听器.
 * <p>
 * 使用 {@link WatchService} 监听插件目录的变更:
 * <ul>
 *   <li>新 JAR 文件出现 → 自动加载并启动</li>
 *   <li>已有 JAR 被替换 → 自动热重载(若插件尚未加载则视为新插件)</li>
 *   <li>JAR 被删除 → 自动卸载(若该插件当前已加载)</li>
 * </ul>
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * DefaultPluginManager manager = new DefaultPluginManager();
 * manager.setPluginsDir(Path.of("plugins"));
 * manager.loadPlugins();
 * manager.startPlugins();
 *
 * // 启动文件监听(try-with-resources 或手动 close)
 * PluginWatcher watcher = new PluginWatcher(manager);
 * // ...
 * watcher.close();
 * }</pre>
 *
 * <p><b>稳定性延迟</b>:文件拷贝操作通常产生多个 MODIFY 事件.
 * 监听器使用 {@link #STABILIZE_DELAY_MS} 毫秒的防抖窗口,
 * 在最后一个事件之后等待文件写入完成再触发热重载.
 *
 * <p><b>线程模型</b>:监听线程是 Daemon 线程,不阻止 JVM 退出.
 * 事件处理在单独的调度线程中执行,与监听线程解耦.
 */
public class PluginWatcher implements AutoCloseable {

    /** 文件写入稳定性等待时间(毫秒),防止 JAR 写入过程中触发热重载 */
    private static final long STABILIZE_DELAY_MS = 500;

    private static final System.Logger LOGGER = System.getLogger(PluginWatcher.class.getName());

    private final DefaultPluginManager manager;
    private final WatchService watchService;
    private final Thread watchThread;
    // pluginId -> jar path(用于删除事件的反查)
    private final Map<Path, String> jarToPluginId = new ConcurrentHashMap<>();
    // 防抖调度器:对每个 JAR 路径记录最后一次变更时间
    private final Map<Path, Long> pendingModify = new ConcurrentHashMap<>();
    private final ScheduledExecutorService debounceExecutor;
    private volatile boolean running = true;

    /**
     * 构造并立即启动文件监听.
     *
     * @param manager 已初始化的 {@link DefaultPluginManager}(需已调用 {@code setPluginsDir})
     * @throws IOException 若无法注册 WatchService 或目录不存在
     */
    public PluginWatcher(DefaultPluginManager manager) throws IOException {
        this.manager = manager;
        Path pluginsDir = manager.getPluginsDir();
        Files.createDirectories(pluginsDir);

        this.watchService = FileSystems.getDefault().newWatchService();
        pluginsDir.register(watchService,
                StandardWatchEventKinds.ENTRY_CREATE,
                StandardWatchEventKinds.ENTRY_MODIFY,
                StandardWatchEventKinds.ENTRY_DELETE);

        // 初始化 jarToPluginId 快照：扫描目录，将已加载的 JAR 路径与插件 ID 关联
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(pluginsDir, "*.jar")) {
            for (Path jar : stream) {
                try {
                    PluginDescriptor desc = PluginDescriptorLoader.load(jar);
                    String existingId = desc.id();
                    // Only map if this plugin is already loaded
                    if (manager.getPlugin(existingId).isPresent()) {
                        jarToPluginId.put(jar.toAbsolutePath().normalize(), existingId);
                    }
                } catch (Exception ignored) {
                    // JAR might be malformed; skip
                }
            }
        }

        this.debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "sunsen-plugin-watcher-debounce");
            t.setDaemon(true);
            return t;
        });

        this.watchThread = Thread.ofPlatform()
                .name("sunsen-plugin-watcher")
                .daemon(true)
                .start(this::watchLoop);
    }

    private void watchLoop() {
        Path pluginsDir = manager.getPluginsDir();
        while (running) {
            WatchKey key;
            try {
                key = watchService.poll(1, TimeUnit.SECONDS);
            } catch (ClosedWatchServiceException e) {
                break;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
            if (key == null) {
                // Fire any debounced events whose window has expired
                flushPending();
                continue;
            }
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();
                if (kind == StandardWatchEventKinds.OVERFLOW) continue;

                @SuppressWarnings("unchecked")
                Path fileName = ((WatchEvent<Path>) event).context();
                if (!fileName.toString().endsWith(".jar")) continue;

                Path fullPath = pluginsDir.resolve(fileName).toAbsolutePath().normalize();

                if (kind == StandardWatchEventKinds.ENTRY_CREATE) {
                    // Delay to let the file write finish
                    pendingModify.put(fullPath, System.currentTimeMillis());
                } else if (kind == StandardWatchEventKinds.ENTRY_MODIFY) {
                    pendingModify.put(fullPath, System.currentTimeMillis());
                } else if (kind == StandardWatchEventKinds.ENTRY_DELETE) {
                    pendingModify.remove(fullPath);
                    handleDelete(fullPath);
                }
            }
            key.reset();
            flushPending();
        }
    }

    /** 将超过防抖窗口的待处理变更触发实际操作 */
    private void flushPending() {
        long now = System.currentTimeMillis();
        pendingModify.entrySet().removeIf(entry -> {
            if (now - entry.getValue() >= STABILIZE_DELAY_MS) {
                Path jar = entry.getKey();
                try {
                    debounceExecutor.submit(() -> handleCreateOrModify(jar));
                } catch (RejectedExecutionException ignored) {
                    // Executor is shutting down (close() called); skip remaining events
                }
                return true;
            }
            return false;
        });
    }

    private void handleCreateOrModify(Path jar) {
        if (!Files.exists(jar)) return;

        PluginDescriptor desc;
        try {
            desc = PluginDescriptorLoader.load(jar);
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.WARNING,
                    () -> "PluginWatcher: cannot parse plugin descriptor from " + jar, e);
            return;
        }

        String pluginId = desc.id();
        PluginState currentState = manager.getPluginState(pluginId);

        if (currentState == null) {
            // New plugin: load + start
            try {
                manager.loadPlugin(jar);
                manager.startPlugin(pluginId);
                jarToPluginId.put(jar, pluginId);
                LOGGER.log(System.Logger.Level.INFO,
                        () -> "PluginWatcher: auto-loaded new plugin " + pluginId + " from " + jar);
            } catch (Exception e) {
                LOGGER.log(System.Logger.Level.ERROR,
                        () -> "PluginWatcher: failed to auto-load plugin from " + jar, e);
            }
        } else if (currentState == PluginState.ACTIVE || currentState == PluginState.STOPPED
                || currentState == PluginState.FAILED) {
            // Existing plugin (including FAILED): hot reload — new JAR may fix the issue
            try {
                manager.reloadPlugin(pluginId, jar);
                jarToPluginId.put(jar, pluginId);
                LOGGER.log(System.Logger.Level.INFO,
                        () -> "PluginWatcher: auto-reloaded plugin " + pluginId + " from " + jar);
            } catch (Exception e) {
                LOGGER.log(System.Logger.Level.ERROR,
                        () -> "PluginWatcher: failed to auto-reload plugin " + pluginId, e);
            }
        }
    }

    private void handleDelete(Path jar) {
        String pluginId = jarToPluginId.remove(jar);
        if (pluginId == null) return;

        PluginState state = manager.getPluginState(pluginId);
        if (state == null || state == PluginState.UNLOADED) return;

        debounceExecutor.submit(() -> {
            try {
                if (manager.getPluginState(pluginId) == PluginState.ACTIVE) {
                    manager.stopPlugin(pluginId);
                }
                manager.unloadPlugin(pluginId);
                LOGGER.log(System.Logger.Level.INFO,
                        () -> "PluginWatcher: auto-unloaded plugin " + pluginId + " (JAR deleted)");
            } catch (PluginLoadException e) {
                LOGGER.log(System.Logger.Level.ERROR,
                        () -> "PluginWatcher: failed to auto-unload plugin " + pluginId, e);
            }
        });
    }

    /**
     * 停止文件监听并释放资源.
     */
    @Override
    public void close() throws Exception {
        running = false;
        watchService.close();
        watchThread.join(3000);
        debounceExecutor.shutdownNow();
    }
}
