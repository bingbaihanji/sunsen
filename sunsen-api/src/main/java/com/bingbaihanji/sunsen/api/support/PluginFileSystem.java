package com.bingbaihanji.sunsen.api.support;

import com.bingbaihanji.sunsen.api.PluginContext;
import com.bingbaihanji.sunsen.api.PluginPermissionException;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.stream.Stream;

/**
 * 插件受限文件系统,限制所有文件操作在插件工作目录({@code pluginsDir/<pluginId>/})内.
 * <p>
 * 如果试图访问工作目录之外的文件(通过 {@code ..} 或其他方式跳出),会抛出
 * {@link PluginPermissionException}.
 * <p>
 * 实现 {@link AutoCloseable},可配合 {@link AbstractPlugin#manage(AutoCloseable)} 使用.
 *
 * <h3>典型用法</h3>
 * <pre>{@code
 * @Override
 * protected void onInitialized() {
 *     fs = manage(PluginFileSystem.of(context()));
 * }
 *
 * @Override
 * public void onStart() {
 *     // 实际写入 pluginsDir/com.example.plugin/config.json
 *     fs.writeString("config.json", "{\"enabled\":true}");
 *
 *     if (fs.exists("data/items.csv")) {
 *         String csv = fs.readString("data/items.csv");
 *     }
 * }
 * }</pre>
 */
public final class PluginFileSystem implements AutoCloseable {

    private final Path workDir;
    private final String pluginId;
    private volatile boolean closed;

    private PluginFileSystem(Path workDir, String pluginId) {
        this.workDir = workDir.toAbsolutePath().normalize();
        this.pluginId = pluginId;
    }

    /**
     * 从插件上下文创建受限文件系统实例.
     *
     * @param context 插件上下文
     */
    public static PluginFileSystem of(PluginContext context) {
        return new PluginFileSystem(context.getPluginWorkDir(), context.getDescriptor().id());
    }

    /**
     * 将相对路径解析为绝对路径,并校验不越界.
     *
     * @param relativePath 相对于工作目录的路径
     * @return 解析后的绝对路径
     * @throws PluginPermissionException 路径试图跳出工作目录
     */
    public Path resolve(String relativePath) {
        ensureOpen();
        Path resolved = workDir.resolve(relativePath).toAbsolutePath().normalize();
        if (!resolved.startsWith(workDir)) {
            throw new PluginPermissionException(
                    "Plugin '" + pluginId + "' attempted to access path outside work directory: " + relativePath
            );
        }
        return resolved;
    }

    /**
     * 检查文件或目录是否存在.
     */
    public boolean exists(String relativePath) {
        return Files.exists(resolve(relativePath));
    }

    /**
     * 检查路径是否为目录.
     */
    public boolean isDirectory(String relativePath) {
        return Files.isDirectory(resolve(relativePath));
    }

    /**
     * 读取文件全部内容为字符串(UTF-8).
     *
     * @throws IOException 读取失败
     */
    public String readString(String relativePath) throws IOException {
        return Files.readString(resolve(relativePath));
    }

    /**
     * 读取文件全部内容为字节数组.
     *
     * @throws IOException 读取失败
     */
    public byte[] readAllBytes(String relativePath) throws IOException {
        return Files.readAllBytes(resolve(relativePath));
    }

    /**
     * 打开输入流读取文件.
     *
     * @throws IOException 打开失败
     */
    public InputStream newInputStream(String relativePath) throws IOException {
        return Files.newInputStream(resolve(relativePath));
    }

    /**
     * 写入字符串到文件(覆盖模式,UTF-8).
     * 父目录不存在时自动创建.
     *
     * @throws IOException 写入失败
     */
    public void writeString(String relativePath, String content) throws IOException {
        Path target = resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    /**
     * 写入字节数组到文件(覆盖模式).
     * 父目录不存在时自动创建.
     *
     * @throws IOException 写入失败
     */
    public void writeAllBytes(String relativePath, byte[] bytes) throws IOException {
        Path target = resolve(relativePath);
        Files.createDirectories(target.getParent());
        Files.write(target, bytes);
    }

    /**
     * 打开输出流写入文件.
     * 父目录不存在时自动创建.
     *
     * @param options 打开选项,默认覆盖模式
     * @throws IOException 打开失败
     */
    public OutputStream newOutputStream(String relativePath, OpenOption... options) throws IOException {
        Path target = resolve(relativePath);
        Files.createDirectories(target.getParent());
        if (options.length == 0) {
            return Files.newOutputStream(target, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        }
        return Files.newOutputStream(target, options);
    }

    /**
     * 创建目录(包括不存在的父目录).
     *
     * @throws IOException 创建失败
     */
    public void createDirectories(String relativePath) throws IOException {
        Files.createDirectories(resolve(relativePath));
    }

    /**
     * 删除文件或空目录.
     *
     * @throws IOException 删除失败
     */
    public void delete(String relativePath) throws IOException {
        Files.delete(resolve(relativePath));
    }

    /**
     * 如果存在则删除.
     *
     * @return true 如果实际执行了删除
     * @throws IOException 删除失败
     */
    public boolean deleteIfExists(String relativePath) throws IOException {
        return Files.deleteIfExists(resolve(relativePath));
    }

    /**
     * 列出目录下的直接子项(文件和目录)名称列表.
     *
     * @throws IOException 列出失败或路径不是目录
     */
    public List<String> list(String relativePath) throws IOException {
        try (Stream<Path> stream = Files.list(resolve(relativePath))) {
            return stream.map(p -> p.getFileName().toString()).toList();
        }
    }

    /**
     * 返回文件大小(字节).
     *
     * @throws IOException 获取失败
     */
    public long size(String relativePath) throws IOException {
        return Files.size(resolve(relativePath));
    }

    /**
     * 返回插件工作目录的根路径.
     */
    public Path getWorkDir() {
        return workDir;
    }

    @Override
    public void close() {
        closed = true;
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("PluginFileSystem for '" + pluginId + "' has been closed");
        }
    }
}
