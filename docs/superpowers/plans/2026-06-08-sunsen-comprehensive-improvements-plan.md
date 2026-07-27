# Sunsen 框架全面改进实现计划

> **对于代理式工作器：** 要求的子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans
> 按任务逐步实现本计划。步骤使用复选框（`- [ ]`）语法进行跟踪。

**目标：** 修复 4 处正确性 Bug，改进 3 处 API 一致性，优化 2 处代码质量，补全 5 个组件的单元测试。

**架构：** 所有修改集中在 sunsen-core（接口 + Javadoc）、sunsen-api（AbstractPlugin）和 sunsen-server（实现 + 测试），必须遵守"
不破坏公开 API"约束。阶段 1-3 按依赖顺序执行（先基础组件后上层组合），阶段 4 测试类无相互依赖可并行编写。

**技术栈：** Java 21+, JUnit 5, Maven surefire 3.2.5, 零新增外部依赖。

---

## 文件结构

### 修改文件

| 文件                                                                                 | 职责                                                             |
|--------------------------------------------------------------------------------------|------------------------------------------------------------------|
| `sunsen-core/src/main/java/com/bingbaihanji/sunsen/api/PluginManager.java`           | Javadoc 修正 (2.3), 已有 close() default 保持不变                |
| `sunsen-core/src/main/java/com/bingbaihanji/sunsen/api/PluginContext.java`           | 新增 getExtension default 方法 (2.1)                             |
| `sunsen-api/src/main/java/com/bingbaihanji/sunsen/api/support/AbstractPlugin.java`   | 新增 extension() 便利方法 (2.1), onDestroy 日志 (3.2)            |
| `sunsen-server/src/main/java/com/bingbaihanji/sunsen/core/PluginEventBus.java`       | 新增 close() 方法 (1.3)                                          |
| `sunsen-server/src/main/java/com/bingbaihanji/sunsen/core/DefaultPluginManager.java` | 修复静默失败 (1.1), 覆盖 close() (1.3)                           |
| `sunsen-server/src/main/java/com/bingbaihanji/sunsen/core/DefaultPluginContext.java` | 修复 reloadConfig 并发 (1.2), PluginManagerView → 代理 (1.4+3.1) |
| `sunsen-server/src/main/java/com/bingbaihanji/sunsen/core/PluginWatcher.java`        | 构造参数改为接口 (2.2)                                           |

### 新增测试文件

| 文件                                                                                       | 被测组件                       |
|--------------------------------------------------------------------------------------------|--------------------------------|
| `sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/SemVerTest.java`                 | SemVer                         |
| `sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/VersionConstraintTest.java`      | VersionConstraint              |
| `sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/PluginEventBusTest.java`         | PluginEventBus                 |
| `sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/PluginDescriptorLoaderTest.java` | PluginDescriptorLoader.parse() |
| `sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/ExtensionScannerTest.java`       | ExtensionScanner 静态方法      |

---

### Task 1: 修复 startPlugin/stopPlugin 静默失败 (Bug 1.1)

**文件：**

- 修改：`sunsen-server/src/main/java/com/bingbaihanji/sunsen/core/DefaultPluginManager.java`

**目标：** startSinglePlugin/stopSinglePlugin 不再吞异常；批量方法保持容错。

- [ ] **Step 1: 修改 startSinglePlugin 让异常传播**

定位到 `startSinglePlugin` 方法（约 L754-L771）。将方法末尾的 catch 块移除：

```java
// 改前（约 L767-L770）
        } catch (Exception e) {
            // executePhase 已发布 PluginFailedEvent
        }

// 改后：直接删除 catch 块，让异常自然传播
```

修改后方法应为 `ACTIVE` 转换失败时 re-throw：

```java
private void startSinglePlugin(String pluginId) {
    PluginEntry entry = plugins.get(pluginId);
    if (entry == null) return;

    PluginState current = stateMachine.getState(pluginId);
    if (current != PluginState.LOADED && current != PluginState.STOPPED) {
        return;
    }
    stateMachine.transition(pluginId, current, PluginState.STARTING);
    stateMachine.executePhase(entry.plugin()::onStart, entry.descriptor(), PluginState.STARTING);
    stateMachine.transition(pluginId, PluginState.STARTING, PluginState.ACTIVE);
    eventBus.publish(new PluginStartedEvent(entry.descriptor()));
}
```

- [ ] **Step 2: 修改 stopSinglePlugin 让异常传播**

同样移除 stopSinglePlugin 方法的 catch 块：

```java
private void stopSinglePlugin(String pluginId) {
    PluginEntry entry = plugins.get(pluginId);
    if (entry == null) return;

    stateMachine.transition(pluginId, PluginState.ACTIVE, PluginState.STOPPING);
    stateMachine.executePhase(entry.plugin()::onStop, entry.descriptor(), PluginState.STOPPING);
    stateMachine.transition(pluginId, PluginState.STOPPING, PluginState.STOPPED);
    eventBus.publish(new PluginStoppedEvent(entry.descriptor()));
}
```

- [ ] **Step 3: startPlugins 批量方法加容错**

在 startPlugins () 批量循环内加 try/catch：

```java
// 改前
for (String pluginId : snapshot) {
    PluginState state = stateMachine.getState(pluginId);
    if (state == PluginState.LOADED) {
        startSinglePlugin(pluginId);
    }
}

// 改后
for (String pluginId : snapshot) {
    PluginState state = stateMachine.getState(pluginId);
    if (state == PluginState.LOADED) {
        try {
            startSinglePlugin(pluginId);
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.ERROR,
                    () -> "Failed to start plugin: " + pluginId, e);
        }
    }
}
```

- [ ] **Step 4: stopPlugins 批量方法加容错**

```java
// 改前
for (String pluginId : snapshot) {
    PluginState state = stateMachine.getState(pluginId);
    if (state == PluginState.ACTIVE) {
        stopSinglePlugin(pluginId);
    }
}

// 改后
for (String pluginId : snapshot) {
    PluginState state = stateMachine.getState(pluginId);
    if (state == PluginState.ACTIVE) {
        try {
            stopSinglePlugin(pluginId);
        } catch (Exception e) {
            LOGGER.log(System.Logger.Level.ERROR,
                    () -> "Failed to stop plugin: " + pluginId, e);
        }
    }
}
```

- [ ] **Step 5: startPlugin 单个方法不再忽略返回状态**

`startPlugin(String)` 内部调用 `startSinglePlugin`，异常现在向上传播：

```java
// 改后
@Override
public void startPlugin(String pluginId) {
    PluginEntry entry = plugins.get(pluginId);
    if (entry == null) {
        throw new PluginLoadException("插件不存在: " + pluginId);
    }
    startSinglePlugin(pluginId);
}
```

- [ ] **Step 6: stopPlugin 单个方法同样处理**

```java
// 改后
@Override
public void stopPlugin(String pluginId) {
    PluginEntry entry = plugins.get(pluginId);
    if (entry == null) {
        throw new PluginLoadException("插件不存在: " + pluginId);
    }
    stopSinglePlugin(pluginId);
}
```

注意 `stopPlugin` 应检查 state 再调 stopSinglePlugin（与 startPlugin 对称）：

```java

@Override
public void stopPlugin(String pluginId) {
    PluginEntry entry = plugins.get(pluginId);
    if (entry == null) {
        throw new PluginLoadException("插件不存在: " + pluginId);
    }
    PluginState state = stateMachine.getState(pluginId);
    if (state != PluginState.ACTIVE) {
        throw new PluginLoadException("插件状态不是 ACTIVE,无法停止: " + pluginId + " (当前状态: " + state + ")");
    }
    stopSinglePlugin(pluginId);
}
```

- [ ] **Step 7: 编译验证**

```bash
mvn compile -pl sunsen-server -am
```

- [ ] **Step 8: 运行现有测试验证无回归**

```bash
mvn test -pl sunsen-server
```

- [ ] **Step 9: 提交**

```bash
git add sunsen-server/src/main/java/com/bingbaihanji/sunsen/core/DefaultPluginManager.java
git commit -m "fix: startPlugin/stopPlugin should propagate lifecycle exceptions

    Removes empty catch blocks in startSinglePlugin/stopSinglePlugin so
    exceptions from onStart/onStop reach the caller. Batch methods
    (startPlugins/stopPlugins) retain fault-isolation via try/catch.

    Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: 修复 reloadConfig 并发窗口 (Bug 1.2)

**文件：**

- 修改：`sunsen-server/src/main/java/com/bingbaihanji/sunsen/core/DefaultPluginContext.java`

**目标：** Properties 读写原子化，消除 clear ()→load () 间的并发窗口。

- [ ] **Step 1: 将 configProperties 改为 volatile，修改读方法加 synchronized**

将字段声明从：

```java
private final Properties configProperties = new Properties();
```

改为：

```java
private volatile Properties configProperties = new Properties();
```

修改 getProperty 为 synchronized：

```java
@Override
public synchronized String getProperty(String key) {
    return configProperties.getProperty(key);
}

@Override
public synchronized String getProperty(String key, String defaultValue) {
    return configProperties.getProperty(key, defaultValue);
}
```

- [ ] **Step 2: 重写 reloadConfig 为原子替换**

```java
// 改前
@Override
public void reloadConfig() {
    configProperties.clear();
    loadConfig();
}

// 改后
@Override
public synchronized void reloadConfig() {
    Properties fresh = new Properties();
    Path configFile = pluginWorkDir.resolve("config.properties");
    if (Files.exists(configFile)) {
        try (InputStream is = Files.newInputStream(configFile)) {
            fresh.load(is);
        } catch (IOException e) {
            throw new PluginLoadException("Failed to load plugin config: " + configFile, e);
        }
    }
    configProperties = fresh;
}
```

- [ ] **Step 3: 删除私有 loadConfig 方法，构造器中内联初始化**

原构造器末尾调 `loadConfig()`，改为构建时直接读取：

```java
// 改前（构造器末尾）
loadConfig();

// 改后
Path configFile = pluginWorkDir.resolve("config.properties");
if (Files.exists(configFile)) {
    try (InputStream is = Files.newInputStream(configFile)) {
        configProperties.load(is);
    } catch (IOException e) {
        throw new PluginLoadException("Failed to load plugin config: " + configFile, e);
    }
}
```

然后删除私有 `loadConfig()` 方法。

- [ ] **Step 4: 编译验证**

```bash
mvn compile -pl sunsen-server -am
```

- [ ] **Step 5: 运行现有测试验证无回归**

```bash
mvn test -pl sunsen-server
```

- [ ] **Step 6: 提交**

```bash
git add sunsen-server/src/main/java/com/bingbaihanji/sunsen/core/DefaultPluginContext.java
git commit -m "fix: make PluginContext.reloadConfig() atomic with volatile swap

    Replaces clear()+load() with fresh Properties construction +
    volatile field assignment. Read methods synchronized for visibility.

    Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: 修复 close () 不关闭 EventBus Executor (Bug 1.3)

**文件：**

- 修改：`sunsen-server/src/main/java/com/bingbaihanji/sunsen/core/PluginEventBus.java`
- 修改：`sunsen-server/src/main/java/com/bingbaihanji/sunsen/core/DefaultPluginManager.java`

**目标：** 异步 EventBus 的线程池在 close 时被正确 shutdown。

- [ ] **Step 1: PluginEventBus 添加 close () 方法**

在 `PluginEventBus.java` 中 `publish()` 方法之后添加：

```java
/**
 * Shut down the underlying Executor if it is an ExecutorService.
 * After this call, no new events will be dispatched.
 */
public void close() {
    if (executor instanceof java.util.concurrent.ExecutorService es) {
        es.shutdown();
    }
}
```

- [ ] **Step 2: DefaultPluginManager 覆盖 close ()**

在 `DefaultPluginManager.java` 中覆盖 `close()` 方法。替换原有的 `loadPluginsInternal()` 之前到 `loadSinglePluginCore`
之前那一大段寻找合适位置；建议在 `setExtensionRegistrar` 方法之后插入：

```java

@Override
public void close() {
    try {
        stopPlugins();
    } finally {
        unloadPlugins();
        eventBus.close();
    }
}
```

注意：`PluginManager` 接口中 `close()` 已是 `default` 方法，这里覆盖它，签名不变。

- [ ] **Step 3: 编译验证**

```bash
mvn compile -pl sunsen-server -am
```

- [ ] **Step 4: 运行现有测试验证无回归**

```bash
mvn test -pl sunsen-server
```

- [ ] **Step 5: 提交**

```bash
git add sunsen-server/src/main/java/com/bingbaihanji/sunsen/core/PluginEventBus.java sunsen-server/src/main/java/com/bingbaihanji/sunsen/core/DefaultPluginManager.java
git commit -m "fix: shutdown EventBus ExecutorService on manager close

    Adds PluginEventBus.close() that shuts down the Executor if it is
    an ExecutorService. DefaultPluginManager.close() overrides the
    default to call eventBus.close() after unloadPlugins().

    Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: PluginManagerView 用动态代理重构 + 修复 close () (Bug 1.4 + 改进 3.1)

**文件：**

- 修改：`sunsen-server/src/main/java/com/bingbaihanji/sunsen/core/DefaultPluginContext.java`

**目标：** 删除 ~130 行内部类 PluginManagerView，替换为 ~35 行动态代理；代理内 close () 为 no-op。

- [ ] **Step 1: 添加 ALLOWED_METHODS 常量和 createManagerView 私有方法**

在 `DefaultPluginContext` 类内部（在 Subscription record 之前或 `getPluginManager()` 方法之前）添加静态常量：

```java
private static final Set<String> ALLOWED_METHODS = Set.of(
        "getPlugin", "getPlugins", "getPluginState", "isPluginActive",
        "getExtensions", "getExtension", "getExtensionInfos",
        "publishEvent", "subscribe", "unsubscribe", "close"
);
```

添加私有方法：

```java

@SuppressWarnings("unchecked")
private PluginManager createManagerView() {
    final PluginManager delegate = pluginManager;
    final DefaultPluginContext context = this;
    return (PluginManager) java.lang.reflect.Proxy.newProxyInstance(
            PluginManager.class.getClassLoader(),
            new Class<?>[]{PluginManager.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if (!ALLOWED_METHODS.contains(name)) {
                    throw new PluginPermissionException(
                            "Plugin cannot invoke " + name + " via PluginContext");
                }
                return switch (name) {
                    case "publishEvent" -> {
                        context.publishEvent((com.bingbaihanji.sunsen.api.event.PluginEvent) args[0]);
                        yield null;
                    }
                    case "subscribe" -> {
                        context.subscribe(
                                (Class<? extends com.bingbaihanji.sunsen.api.event.PluginEvent>) args[0],
                                (com.bingbaihanji.sunsen.api.event.PluginEventListener<?>) args[1]);
                        yield null;
                    }
                    case "unsubscribe" -> {
                        context.unsubscribe(
                                (Class<? extends com.bingbaihanji.sunsen.api.event.PluginEvent>) args[0],
                                (com.bingbaihanji.sunsen.api.event.PluginEventListener<?>) args[1]);
                        yield null;
                    }
                    case "close" -> null;
                    default -> method.invoke(delegate, args);
                };
            }
    );
}
```

- [ ] **Step 2: 修改 getPluginManager () 方法**

```java
// 改前
@Override
public PluginManager getPluginManager() {
    return new PluginManagerView(pluginManager);
}

// 改后
@Override
public PluginManager getPluginManager() {
    return createManagerView();
}
```

- [ ] **Step 3: 删除整个 PluginManagerView 内部类**

删除从 `private class PluginManagerView implements PluginManager {` 到 `}` 的全部内容（约 125 行）。

- [ ] **Step 4: 编译验证**

```bash
mvn compile -pl sunsen-server -am
```

- [ ] **Step 5: 运行现有测试验证无回归 —— 尤其验证插件仍无法调用管理类方法**

```bash
mvn test -pl sunsen-server
```

- [ ] **Step 6: 提交**

```bash
git add sunsen-server/src/main/java/com/bingbaihanji/sunsen/core/DefaultPluginContext.java
git commit -m "refactor: replace PluginManagerView inner class with dynamic proxy

    Reduces ~130 lines of repetitive throw-wrappers to ~35 lines.
    close() is now a no-op on the proxy, fixing the confusing
    PluginPermissionException from the inherited default close().

    Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 5: PluginContext 添加 getExtension (extensionPoint, id) (改进 2.1)

**文件：**

- 修改：`sunsen-core/src/main/java/com/bingbaihanji/sunsen/api/PluginContext.java`
- 修改：`sunsen-api/src/main/java/com/bingbaihanji/sunsen/api/support/AbstractPlugin.java`

**目标：** 为 PluginContext 和 AbstractPlugin 补全按 ID 精确获取扩展的方法。

- [ ] **Step 1: PluginContext 接口添加 default 方法**

在 `PluginContext.java` 中 `getExtensions` 方法之后插入：

```java
/**
 * 按 ID 精确获取指定扩展点的单个扩展实例.
 *
 * @param extensionPoint 扩展点接口类型
 * @param extensionId    扩展 ID（对应 {@code @Extension.id()} 的值）
 * @return 扩展实例，未找到时返回 {@link Optional#empty()}
 */
default <T> Optional<T> getExtension(Class<T> extensionPoint, String extensionId) {
    List<ExtensionInfo> infos = getExtensionInfos(extensionPoint);
    List<T> exts = getExtensions(extensionPoint);
    for (int i = 0; i < infos.size() && i < exts.size(); i++) {
        if (infos.get(i).extensionId().equals(extensionId)) {
            return Optional.of(exts.get(i));
        }
    }
    return Optional.empty();
}
```

需要添加 `import java.util.Optional;`（检查现有导入，若已有则略过）。

- [ ] **Step 2: AbstractPlugin 添加便利方法**

在 `AbstractPlugin.java` 中 `extensionInfos` 方法之后插入：

```java
/**
 * 按 ID 获取单个扩展实例.
 */
protected final <T> Optional<T> extension(Class<T> extensionPoint, String extensionId) {
    return context.getExtension(extensionPoint, extensionId);
}
```

- [ ] **Step 3: 编译验证**

```bash
mvn compile -pl sunsen-server -am
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -pl sunsen-server
```

- [ ] **Step 5: 提交**

```bash
git add sunsen-core/src/main/java/com/bingbaihanji/sunsen/api/PluginContext.java sunsen-api/src/main/java/com/bingbaihanji/sunsen/api/support/AbstractPlugin.java
git commit -m "feat: add getExtension(extensionPoint, id) to PluginContext

    Adds a default method on PluginContext and a convenience method
    on AbstractPlugin for looking up a single extension by its ID.
    Non-breaking: uses default method on the interface.

    Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 6: PluginWatcher 构造参数改为接口 (改进 2.2)

**文件：**

- 修改：`sunsen-server/src/main/java/com/bingbaihanji/sunsen/core/PluginWatcher.java`

**目标：** 构造参数从 `DefaultPluginManager` 改为 `PluginManager`，保留旧构造函数 @Deprecated。

- [ ] **Step 1: 修改字段声明和方法签名**

```java
// 改前
private final DefaultPluginManager manager;

// 改后
private final PluginManager manager;
private final Path pluginsDir;
```

- [ ] **Step 2: 添加新的主构造函数**

```java
/**
 * 构造并立即启动文件监听.
 *
 * @param manager    已初始化的插件管理器
 * @param pluginsDir 插件监控目录
 * @throws IOException 若无法注册 WatchService 或目录不存在
 */
public PluginWatcher(PluginManager manager, Path pluginsDir) throws IOException {
    this.manager = manager;
    this.pluginsDir = pluginsDir;
    Files.createDirectories(pluginsDir);
    // ... 其余构造函数体不变，但注意 this.manager.getPluginsDir() 调用要替换为 this.pluginsDir
    // ...
}
```

- [ ] **Step 3: 保留旧构造函数为 @Deprecated**

```java
/**
 * @deprecated 使用 {@link #PluginWatcher(PluginManager, Path)} 代替
 */
@Deprecated
public PluginWatcher(DefaultPluginManager manager) throws IOException {
    this(manager, manager.getPluginsDir());
}
```

- [ ] **Step 4: 替换构造函数体内所有 `manager.getPluginsDir()` 为 `this.pluginsDir`**

构造函数内有两处使用 `manager.getPluginsDir()`：

- 注册 WatchService 时的目录：`pluginsDir.register(watchService, ...)`（改为 `this.pluginsDir.register(...)`）
- watchLoop 中的 `Path pluginsDir = manager.getPluginsDir();` 改为使用字段 `this.pluginsDir`

- [ ] **Step 5: 替换 handleCreateOrModify 中的强制调用**

`handleCreateOrModify` 中调用 `manager.loadPlugin(jar)`、`manager.startPlugin(pluginId)`、
`manager.reloadPlugin(pluginId, jar)`，这些是 `PluginManager` 接口方法，无需改动。

`handleDelete` 中 `manager.stopPlugin(pluginId)`、`manager.unloadPlugin(pluginId)` 同理，无需改动。

但 `getPluginState(String)` 返回 `PluginState` 而非可空——实际上 `PluginManager.getPluginState()` 可以返回 null（根据
Javadoc：插件未知时返回 null），所以 `manager.getPluginState(pluginId) == null` 的检查保持不变。

- [ ] **Step 6: 编译验证**

```bash
mvn compile -pl sunsen-server -am
```

- [ ] **Step 7: 运行测试**

```bash
mvn test -pl sunsen-server
```

- [ ] **Step 8: 提交**

```bash
git add sunsen-server/src/main/java/com/bingbaihanji/sunsen/core/PluginWatcher.java
git commit -m "refactor: make PluginWatcher accept PluginManager interface

    Constructor now takes PluginManager + Path instead of
    DefaultPluginManager, enabling host app to substitute alternate
    PluginManager implementations. Old constructor kept @Deprecated.

    Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 7: 修正 unloadPlugins Javadoc (改进 2.3)

**文件：**

- 修改：`sunsen-core/src/main/java/com/bingbaihanji/sunsen/api/PluginManager.java`

**目标：** Javadoc 描述与实际行为一致。

- [ ] **Step 1: 修改 Javadoc**

```java
// 改前
/**
 * 批量卸载所有 STOPPED 状态的插件，释放 ClassLoader
 */

// 改后
/**
 * 批量停止并卸载所有插件，释放 ClassLoader。
 * 对 ACTIVE 状态的插件先执行 stop 再 unload；
 * 对已 STOPPED 或 FAILED 的插件直接 unload。
 * 按拓扑逆序执行，确保被依赖的插件最后卸载。
 */
```

- [ ] **Step 2: 编译验证**

```bash
mvn compile -pl sunsen-server -am
```

- [ ] **Step 3: 提交**

```bash
git add sunsen-core/src/main/java/com/bingbaihanji/sunsen/api/PluginManager.java
git commit -m "docs: fix unloadPlugins Javadoc to match actual behavior

    The method actually stops ACTIVE plugins before unloading them,
    not only unloads STOPPED ones as the old Javadoc claimed.

    Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 8: AbstractPlugin.onDestroy () 关闭异常改为日志 (改进 3.2)

**文件：**

- 修改：`sunsen-api/src/main/java/com/bingbaihanji/sunsen/api/support/AbstractPlugin.java`

**目标：** 托管资源关闭失败时输出 WARNING 日志而非完全静默。

- [ ] **Step 1: 添加 LOGGER 常量**

在类顶部添加：

```java
private static final System.Logger LOGGER = System.getLogger(AbstractPlugin.class.getName());
```

- [ ] **Step 2: 修改 onDestroy 循环中的 catch 块**

```java
// 改前
} catch (Exception ignored) {
}

// 改后
} catch (Exception e) {
    LOGGER.log(System.Logger.Level.WARNING,
            () -> pluginId() + ": failed to close managed resource "
                  + managedResources.get(i).getClass().getName(), e);
}
```

注意：`pluginId()` 间接调用 `context().getDescriptor().id()` —— 此时 `context` 尚未置空（`context = null` 在循环之后执行），所以调用安全。

- [ ] **Step 3: 编译验证**

```bash
mvn compile -pl sunsen-server -am
```

- [ ] **Step 4: 运行测试**

```bash
mvn test -pl sunsen-server
```

- [ ] **Step 5: 提交**

```bash
git add sunsen-api/src/main/java/com/bingbaihanji/sunsen/api/support/AbstractPlugin.java
git commit -m "fix: log managed resource close failures in AbstractPlugin.onDestroy

    Replaces silent catch with WARNING-level log so plugin developers
    can detect resources that fail to release cleanly.

    Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 9: SemVer 单元测试

**文件：**

- 创建：`sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/SemVerTest.java`

**目标：** 全面覆盖 SemVer.parse () 和 compareTo ()。

- [ ] **Step 1: 编写测试类**

```java
package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.core.version.SemVer;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class SemVerTest {

    @Test
    void parseThreeSegments() {
        SemVer v = SemVer.parse("1.2.3");
        assertEquals(1, v.major());
        assertEquals(2, v.minor());
        assertEquals(3, v.patch());
    }

    @Test
    void parseVPrefix() {
        SemVer v = SemVer.parse("v2.0.0");
        assertEquals(2, v.major());
        assertEquals(0, v.minor());
        assertEquals(0, v.patch());
    }

    @Test
    void parseCapitalVPrefix() {
        SemVer v = SemVer.parse("V3.1.0");
        assertEquals(3, v.major());
    }

    @Test
    void parseTwoSegments() {
        SemVer v = SemVer.parse("1.0");
        assertEquals(1, v.major());
        assertEquals(0, v.minor());
        assertEquals(0, v.patch());
    }

    @Test
    void parseWithPrerelease() {
        SemVer v = SemVer.parse("1.2.3-alpha");
        assertEquals(1, v.major());
        assertEquals(2, v.minor());
        assertEquals(3, v.patch());
    }

    @Test
    void parseWithBuildMetadata() {
        SemVer v = SemVer.parse("1.0.0+build");
        assertEquals(1, v.major());
        assertEquals(0, v.minor());
        assertEquals(0, v.patch());
    }

    @Test
    void parseNullThrows() {
        assertThrows(IllegalArgumentException.class, () -> SemVer.parse(null));
    }

    @Test
    void parseBlankThrows() {
        assertThrows(IllegalArgumentException.class, () -> SemVer.parse("  "));
    }

    @Test
    void parseNonNumericThrows() {
        assertThrows(IllegalArgumentException.class, () -> SemVer.parse("abc"));
    }

    @Test
    void parseSingleNumberThrows() {
        assertThrows(IllegalArgumentException.class, () -> SemVer.parse("1"));
    }

    @Test
    void compareToAscendingOrder() {
        SemVer v000 = SemVer.parse("0.0.0");
        SemVer v100 = SemVer.parse("1.0.0");
        SemVer v101 = SemVer.parse("1.0.1");
        SemVer v110 = SemVer.parse("1.1.0");
        SemVer v200 = SemVer.parse("2.0.0");

        assertTrue(v000.compareTo(v100) < 0);
        assertTrue(v100.compareTo(v101) < 0);
        assertTrue(v101.compareTo(v110) < 0);
        assertTrue(v110.compareTo(v200) < 0);
        assertTrue(v200.compareTo(v100) > 0);
    }

    @Test
    void compareToEqual() {
        SemVer a = SemVer.parse("1.2.3");
        SemVer b = SemVer.parse("1.2.3");
        assertEquals(0, a.compareTo(b));
    }

    @Test
    void toStringReturnsStandardFormat() {
        SemVer v = SemVer.parse("1.2.3");
        assertEquals("1.2.3", v.toString());
    }

    @Test
    void parsePreReleaseSuffixWithDotInVersion() {
        SemVer v = SemVer.parse("1.2.3-alpha.1+build.42");
        assertEquals(1, v.major());
        assertEquals(2, v.minor());
        assertEquals(3, v.patch());
    }
}
```

- [ ] **Step 2: 运行测试验证通过**

```bash
mvn test -pl sunsen-server -Dtest=SemVerTest
```

- [ ] **Step 3: 提交**

```bash
git add sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/SemVerTest.java
git commit -m "test: add SemVerTest covering parse, compareTo, edge cases

    Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 10: VersionConstraint 单元测试

**文件：**

- 创建：`sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/VersionConstraintTest.java`

**目标：** 覆盖所有约束解析格式和匹配逻辑。

- [ ] **Step 1: 编写测试类**

```java
package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.core.version.VersionConstraint;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class VersionConstraintTest {

    @Test
    void caretConstraintMatchesWithinMajor() {
        VersionConstraint c = VersionConstraint.parse("^1.2.0");
        assertTrue(c.matches("1.2.0"));
        assertTrue(c.matches("1.9.9"));
    }

    @Test
    void caretConstraintRejectsDifferentMajor() {
        VersionConstraint c = VersionConstraint.parse("^1.2.0");
        assertFalse(c.matches("2.0.0"));
        assertFalse(c.matches("0.9.0"));
    }

    @Test
    void caretConstraintRejectsBeforeBase() {
        VersionConstraint c = VersionConstraint.parse("^1.2.0");
        assertFalse(c.matches("1.1.9"));
    }

    @Test
    void tildeConstraintMatchesWithinMinor() {
        VersionConstraint c = VersionConstraint.parse("~1.2.0");
        assertTrue(c.matches("1.2.0"));
        assertTrue(c.matches("1.2.9"));
    }

    @Test
    void tildeConstraintRejectsDifferentMinor() {
        VersionConstraint c = VersionConstraint.parse("~1.2.0");
        assertFalse(c.matches("1.3.0"));
        assertFalse(c.matches("2.0.0"));
    }

    @Test
    void rangeConstraintAcceptsWithinRange() {
        VersionConstraint c = VersionConstraint.parse(">=1.0.0 <2.0.0");
        assertTrue(c.matches("1.0.0"));
        assertTrue(c.matches("1.5.0"));
        assertTrue(c.matches("1.9.9"));
    }

    @Test
    void rangeConstraintRejectsOutsideRange() {
        VersionConstraint c = VersionConstraint.parse(">=1.0.0 <2.0.0");
        assertFalse(c.matches("0.9.0"));
        assertFalse(c.matches("2.0.0"));
    }

    @Test
    void exactConstraintMatchesOnlyExact() {
        VersionConstraint c = VersionConstraint.parse("1.2.3");
        assertTrue(c.matches("1.2.3"));
        assertFalse(c.matches("1.2.4"));
        assertFalse(c.matches("1.3.0"));
    }

    @Test
    void notEqualConstraint() {
        VersionConstraint c = VersionConstraint.parse("!=1.0.0");
        assertFalse(c.matches("1.0.0"));
        assertTrue(c.matches("1.0.1"));
        assertTrue(c.matches("2.0.0"));
    }

    @Test
    void nullExpressionThrows() {
        assertThrows(IllegalArgumentException.class, () -> VersionConstraint.parse(null));
    }

    @Test
    void blankExpressionThrows() {
        assertThrows(IllegalArgumentException.class, () -> VersionConstraint.parse("  "));
    }

    @Test
    void combinedConstraints() {
        VersionConstraint c = VersionConstraint.parse(">=1.0.0 <=1.5.0");
        assertTrue(c.matches("1.0.0"));
        assertTrue(c.matches("1.5.0"));
        assertFalse(c.matches("1.5.1"));
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
mvn test -pl sunsen-server -Dtest=VersionConstraintTest
```

- [ ] **Step 3: 提交**

```bash
git add sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/VersionConstraintTest.java
git commit -m "test: add VersionConstraintTest covering all constraint formats

    Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 11: PluginEventBus 单元测试

**文件：**

- 创建：`sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/PluginEventBusTest.java`

**目标：** 覆盖订阅匹配、父类型分发、取消、异常隔离和同步线程模型。

- [ ] **Step 1: 编写测试类**

```java
package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.event.PluginEvent;
import com.bingbaihanji.sunsen.api.event.builtin.*;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class PluginEventBusTest {

    @Test
    void exactTypeMatching() {
        PluginEventBus bus = new PluginEventBus();
        AtomicInteger counter = new AtomicInteger();
        bus.subscribe(PluginLoadedEvent.class, e -> counter.incrementAndGet());

        bus.publish(new PluginLoadedEvent(descriptor("test", "1.0")));
        assertEquals(1, counter.get());
    }

    @Test
    void noMatchOnDifferentType() {
        PluginEventBus bus = new PluginEventBus();
        AtomicInteger counter = new AtomicInteger();
        bus.subscribe(PluginLoadedEvent.class, e -> counter.incrementAndGet());

        bus.publish(new PluginStartedEvent(descriptor("test", "1.0")));
        assertEquals(0, counter.get());
    }

    @Test
    void parentTypeSubscriptionReceivesAll() {
        PluginEventBus bus = new PluginEventBus();
        AtomicInteger counter = new AtomicInteger();
        bus.subscribe(PluginEvent.class, e -> counter.incrementAndGet());

        bus.publish(new PluginLoadedEvent(descriptor("a", "1.0")));
        bus.publish(new PluginStartedEvent(descriptor("a", "1.0")));
        bus.publish(new PluginStoppedEvent(descriptor("a", "1.0")));
        bus.publish(new PluginUnloadedEvent(descriptor("a", "1.0")));
        bus.publish(new PluginFailedEvent(descriptor("a", "1.0"), null, new Exception()));

        assertEquals(5, counter.get());
    }

    @Test
    void unsubscribeStopsDelivery() {
        PluginEventBus bus = new PluginEventBus();
        AtomicInteger counter = new AtomicInteger();
        var listener = (PluginLoadedEvent e) -> counter.incrementAndGet();
        bus.subscribe(PluginLoadedEvent.class, listener);

        bus.publish(new PluginLoadedEvent(descriptor("a", "1.0")));
        bus.unsubscribe(PluginLoadedEvent.class, listener);
        bus.publish(new PluginLoadedEvent(descriptor("a", "1.0")));

        assertEquals(1, counter.get());
    }

    @Test
    void unsubscribeAllByPluginId() {
        PluginEventBus bus = new PluginEventBus();
        AtomicInteger counter = new AtomicInteger();

        bus.subscribe(PluginLoadedEvent.class, e -> counter.incrementAndGet(), "plugin-A");
        bus.subscribe(PluginStartedEvent.class, e -> counter.addAndGet(10), "plugin-A");
        bus.subscribe(PluginStoppedEvent.class, e -> counter.addAndGet(100), "plugin-B");

        bus.unsubscribeAll("plugin-A");

        bus.publish(new PluginLoadedEvent(descriptor("a", "1.0")));
        bus.publish(new PluginStartedEvent(descriptor("a", "1.0")));
        bus.publish(new PluginStoppedEvent(descriptor("a", "1.0")));

        assertEquals(100, counter.get()); // only plugin-B's subscription remains
    }

    @Test
    void listenerExceptionDoesNotAffectOthers() {
        PluginEventBus bus = new PluginEventBus();
        AtomicInteger counter = new AtomicInteger();

        bus.subscribe(PluginLoadedEvent.class, e -> { throw new RuntimeException("boom"); });
        bus.subscribe(PluginLoadedEvent.class, e -> counter.incrementAndGet());

        bus.publish(new PluginLoadedEvent(descriptor("a", "1.0")));
        assertEquals(1, counter.get());
    }

    @Test
    void syncDispatchRunsOnCallerThread() {
        PluginEventBus bus = new PluginEventBus();
        Thread callerThread = Thread.currentThread();
        List<Thread> dispatchThreads = new CopyOnWriteArrayList<>();

        bus.subscribe(PluginLoadedEvent.class, e -> dispatchThreads.add(Thread.currentThread()));
        bus.publish(new PluginLoadedEvent(descriptor("a", "1.0")));

        assertEquals(1, dispatchThreads.size());
        assertSame(callerThread, dispatchThreads.get(0));
    }

    private static PluginDescriptor descriptor(String id, String version) {
        return new PluginDescriptor(id, "Test", "", version, "1.0", "com.test.Main",
                List.of("com.test"), List.of(), java.util.Set.of(), java.util.Map.of());
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
mvn test -pl sunsen-server -Dtest=PluginEventBusTest
```

- [ ] **Step 3: 提交**

```bash
git add sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/PluginEventBusTest.java
git commit -m "test: add PluginEventBusTest covering subscription and dispatch

    Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 12: PluginDescriptorLoader 单元测试

**文件：**

- 创建：`sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/PluginDescriptorLoaderTest.java`

**目标：** 覆盖 plugin.json JSON -> PluginDescriptor 解析路径。

- [ ] **Step 1: 编写测试类**

```java
package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.PluginLoadException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PluginDescriptorLoaderTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void parseCompleteJson() throws Exception {
        var root = MAPPER.readTree("""
                {
                  "id": "com.example.plugin",
                  "name": "Example",
                  "description": "An example plugin",
                  "version": "1.2.3",
                  "apiVersion": "1.0",
                  "mainClass": "com.example.Main",
                  "packagePrefixes": ["com.example"],
                  "dependencies": [
                    {"id": "com.example.dep", "version": "^1.0.0"}
                  ],
                  "permissions": ["file:read"],
                  "vendor": {"name": "ACME", "url": "https://example.com"}
                }
                """);

        var descriptor = PluginDescriptorLoader.parse(root);

        assertEquals("com.example.plugin", descriptor.id());
        assertEquals("Example", descriptor.name());
        assertEquals("An example plugin", descriptor.description());
        assertEquals("1.2.3", descriptor.version());
        assertEquals("1.0", descriptor.apiVersion());
        assertEquals("com.example.Main", descriptor.mainClass());
        assertEquals(List.of("com.example"), descriptor.packagePrefixes());
        assertEquals(1, descriptor.dependencies().size());
        assertEquals("com.example.dep", descriptor.dependencies().get(0).id());
        assertEquals("^1.0.0", descriptor.dependencies().get(0).versionConstraint());
        assertFalse(descriptor.dependencies().get(0).optional());
        assertTrue(descriptor.permissions().contains("file:read"));
        assertEquals("ACME", descriptor.vendorInfo().get("name"));
    }

    @Test
    void parseMissingIdThrows() throws Exception {
        var root = MAPPER.readTree("""
                {"name": "X", "version": "1.0", "apiVersion": "1.0",
                 "mainClass": "c.M", "packagePrefixes": ["x"]}
                """);
        assertThrows(PluginLoadException.class, () -> PluginDescriptorLoader.parse(root));
    }

    @Test
    void parseMissingNameThrows() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "version": "1.0", "apiVersion": "1.0",
                 "mainClass": "c.M", "packagePrefixes": ["x"]}
                """);
        assertThrows(PluginLoadException.class, () -> PluginDescriptorLoader.parse(root));
    }

    @Test
    void parseMissingVersionThrows() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "name": "X", "apiVersion": "1.0",
                 "mainClass": "c.M", "packagePrefixes": ["x"]}
                """);
        assertThrows(PluginLoadException.class, () -> PluginDescriptorLoader.parse(root));
    }

    @Test
    void parseMissingApiVersionThrows() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "name": "X", "version": "1.0",
                 "mainClass": "c.M", "packagePrefixes": ["x"]}
                """);
        assertThrows(PluginLoadException.class, () -> PluginDescriptorLoader.parse(root));
    }

    @Test
    void parseMissingMainClassThrows() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "name": "X", "version": "1.0", "apiVersion": "1.0",
                 "packagePrefixes": ["x"]}
                """);
        assertThrows(PluginLoadException.class, () -> PluginDescriptorLoader.parse(root));
    }

    @Test
    void parseMissingPackagePrefixesThrows() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "name": "X", "version": "1.0", "apiVersion": "1.0",
                 "mainClass": "c.M"}
                """);
        assertThrows(PluginLoadException.class, () -> PluginDescriptorLoader.parse(root));
    }

    @Test
    void parseEmptyPackagePrefixesThrows() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "name": "X", "version": "1.0", "apiVersion": "1.0",
                 "mainClass": "c.M", "packagePrefixes": []}
                """);
        assertThrows(PluginLoadException.class, () -> PluginDescriptorLoader.parse(root));
    }

    @Test
    void parseTrailingDotPrefixNormalized() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "name": "X", "version": "1.0", "apiVersion": "1.0",
                 "mainClass": "c.M", "packagePrefixes": ["com.foo."]}
                """);
        var descriptor = PluginDescriptorLoader.parse(root);
        assertEquals("com.foo", descriptor.packagePrefixes().get(0));
    }

    @Test
    void parseDuplicateDependencyIdThrows() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "name": "X", "version": "1.0", "apiVersion": "1.0",
                 "mainClass": "c.M", "packagePrefixes": ["x"],
                 "dependencies": [
                   {"id": "dep", "version": "1.0"},
                   {"id": "dep", "version": "2.0"}
                 ]}
                """);
        assertThrows(PluginLoadException.class, () -> PluginDescriptorLoader.parse(root));
    }

    @Test
    void parseSelfDependencyThrows() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "name": "X", "version": "1.0", "apiVersion": "1.0",
                 "mainClass": "c.M", "packagePrefixes": ["x"],
                 "dependencies": [{"id": "x", "version": "1.0"}]}
                """);
        assertThrows(PluginLoadException.class, () -> PluginDescriptorLoader.parse(root));
    }

    @Test
    void parseOptionalDependency() throws Exception {
        var root = MAPPER.readTree("""
                {"id": "x", "name": "X", "version": "1.0", "apiVersion": "1.0",
                 "mainClass": "c.M", "packagePrefixes": ["x"],
                 "dependencies": [{"id": "dep", "version": "1.0", "optional": true}]}
                """);
        var descriptor = PluginDescriptorLoader.parse(root);
        assertTrue(descriptor.dependencies().get(0).optional());
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
mvn test -pl sunsen-server -Dtest=PluginDescriptorLoaderTest
```

- [ ] **Step 3: 提交**

```bash
git add sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/PluginDescriptorLoaderTest.java
git commit -m "test: add PluginDescriptorLoaderTest covering JSON parsing

    Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 13: ExtensionScanner 单元测试

**文件：**

- 创建：`sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/ExtensionScannerTest.java`

**目标：** 覆盖 `inferExtensionPointTypes()` 和 `validateSingleExtensions()`。

- [ ] **Step 1: 在测试目录下创建扩展点接口和实现类**

这些是测试内部类，直接定义在 ExtensionScannerTest.java 中：

```java
package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.PluginLoadException;
import com.bingbaihanji.sunsen.api.annotation.Extension;
import com.bingbaihanji.sunsen.api.annotation.ExtensionPoint;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ExtensionScannerTest {

    // ---- Test types ----

    @ExtensionPoint
    interface Greeting {
        String say();
    }

    @ExtensionPoint(allowMultiple = false)
    interface SingleUse {
        String use();
    }

    @ExtensionPoint(allowMultiple = true)
    interface MultiUse {
        String use();
    }

    interface NotAnExtensionPoint {
        void foo();
    }

    static class MixedInterface implements Greeting, NotAnExtensionPoint {
        public String say() { return "hi"; }
    }

    static abstract class AbstractGreeting implements Greeting {}

    @Extension
    static class ConcreteGreeting extends AbstractGreeting {
        public String say() { return "hello"; }
    }

    static class PureNotExtension implements NotAnExtensionPoint {
        public void foo() {}
    }

    @Extension(id = "a", order = 0)
    static class SingleA implements SingleUse { public String use() { return "a"; } }

    @Extension(id = "b", order = 1)
    static class SingleB implements SingleUse { public String use() { return "b"; } }

    @Extension
    static class MultiA implements MultiUse { public String use() { return "a"; } }

    @Extension
    static class MultiB implements MultiUse { public String use() { return "b"; } }

    // ---- Tests ----

    @Test
    void inferExtensionPointTypesDirectInterface() {
        List<Class<?>> types = ExtensionScanner.inferExtensionPointTypes(MixedInterface.class);
        assertTrue(types.contains(Greeting.class));
        assertFalse(types.contains(NotAnExtensionPoint.class));
    }

    @Test
    void inferExtensionPointTypesThroughAbstractParent() {
        List<Class<?>> types = ExtensionScanner.inferExtensionPointTypes(ConcreteGreeting.class);
        assertTrue(types.contains(Greeting.class));
    }

    @Test
    void inferExtensionPointTypesOnlyNonExtensionPoint() {
        List<Class<?>> types = ExtensionScanner.inferExtensionPointTypes(PureNotExtension.class);
        assertTrue(types.isEmpty());
    }

    @Test
    void validateSingleExtensionsAllowMultipleFalseTwoImplsThrows() {
        Map<Class<?>, List<Class<?>>> map = Map.of(
                SingleUse.class, List.of(SingleA.class, SingleB.class)
        );
        ExtensionScanner scanner = new ExtensionScanner(null);
        assertThrows(PluginLoadException.class, () -> scanner.validateSingleExtensions(map));
    }

    @Test
    void validateSingleExtensionsAllowMultipleTrueAllowsMultiple() {
        Map<Class<?>, List<Class<?>>> map = Map.of(
                MultiUse.class, List.of(MultiA.class, MultiB.class)
        );
        ExtensionScanner scanner = new ExtensionScanner(null);
        assertDoesNotThrow(() -> scanner.validateSingleExtensions(map));
    }

    @Test
    void validateSingleExtensionsAllowMultipleFalseOneImplPasses() {
        Map<Class<?>, List<Class<?>>> map = Map.of(
                SingleUse.class, List.of(SingleA.class)
        );
        ExtensionScanner scanner = new ExtensionScanner(null);
        assertDoesNotThrow(() -> scanner.validateSingleExtensions(map));
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
mvn test -pl sunsen-server -Dtest=ExtensionScannerTest
```

- [ ] **Step 3: 提交**

```bash
git add sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/ExtensionScannerTest.java
git commit -m "test: add ExtensionScannerTest covering type inference and validation

    Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 14: 全量测试最终验证

- [ ] **Step 1: 跑完整测试套件**

```bash
mvn test -pl sunsen-server
```

- [ ] **Step 2: 确认全部通过**

预期：所有 12 个测试类全部 PASS（原有 7 个 + 新增 5 个）。

---

## 自审清单

1. **规范覆盖** — 对照规范中的每项要求：
    - Bug 1.1 (静默失败) → Task 1 ✓
    - Bug 1.2 (reloadConfig 并发) → Task 2 ✓
    - Bug 1.3 (close () Executor) → Task 3 ✓
    - Bug 1.4 (PluginManagerView.close ()) → Task 4 ✓
    - 改进 2.1 (PluginContext.getExtension) → Task 5 ✓
    - 改进 2.2 (PluginWatcher 接口) → Task 6 ✓
    - 改进 2.3 (Javadoc) → Task 7 ✓
    - 改进 3.1 (PluginManagerView 代理) → Task 4 ✓
    - 改进 3.2 (onDestroy 日志) → Task 8 ✓
    - 测试 SemVer → Task 9 ✓
    - 测试 VersionConstraint → Task 10 ✓
    - 测试 PluginEventBus → Task 11 ✓
    - 测试 PluginDescriptorLoader → Task 12 ✓
    - 测试 ExtensionScanner → Task 13 ✓

2. **占位符扫描** — 无 TBD / TODO / "Add appropriate error handling" 等模式 ✓

3. **类型一致性** — 所有 import 完整，测试类使用现有 JUnit 5 API，EventListener lambda 签名与接口匹配 ✓

4. **checklist 语法** — 所有步骤使用 `- [ ]` ✓
