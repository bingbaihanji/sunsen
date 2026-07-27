# Sunsen 框架全面改进设计文档

**日期**：2026-06-08  
**版本**：1.0  
**范围**：正确性修复 + API 一致性 + 代码质量 + 单元测试补全

---

## 背景与目标

Sunsen 是一个轻量级 Java 插件框架，核心功能已完善，但代码审查发现以下问题：

- **4 处正确性 Bug**：会导致静默失败、并发数据损坏或资源泄漏
- **3 处 API 不一致**：`PluginContext` 与 `PluginManager` API 不对称，`PluginWatcher` 强依赖具体类，文档误导
- **2 处代码质量问题**：`PluginManagerView` 极度重复，资源关闭失败静默
- **5 个组件缺乏单元测试**：`SemVer`、`VersionConstraint`、`PluginEventBus`、`PluginDescriptorLoader`、`ExtensionScanner`

**约束**：

- 不得对公开接口（`PluginManager`、`PluginContext`、`Plugin`）做破坏性变更
- 新增接口方法只能用 `default` 关键字，保持二进制兼容
- 不引入新外部依赖

---

## 阶段 1：正确性修复

### 1.1 `startPlugin()` / `stopPlugin()` 静默失败

**问题文件**：`sunsen-server/.../DefaultPluginManager.java`

**根因**：`startSinglePlugin()` 和 `stopSinglePlugin()` 内有空 `catch` 块，`executePhase` 抛出的异常被完全吞掉。

```java
// 现状（错误）
}catch(Exception e){
        // executePhase 已发布 PluginFailedEvent  ← 异常被吞掉
        }
```

**修复**：

1. `startSinglePlugin()` 和 `stopSinglePlugin()` 移除空 `catch`，让异常向上传播
2. `startPlugins()` 和 `stopPlugins()`（批量方法）在各自循环内加 `try/catch` + 日志，保持"一个失败不中断其他"的语义
3. `startPlugin(String)` 和 `stopPlugin(String)`（单个方法）不加 `try/catch`，让异常自然传播给调用者

```java
// startPlugins() 批量——保持容错
for(String pluginId :snapshot){
        if(stateMachine.

getState(pluginId) ==PluginState.LOADED){
        try{

startSinglePlugin(pluginId);
        }catch(
Exception e){
        LOGGER.

log(Level.ERROR, () ->"Failed to start plugin: "+pluginId,e);
        }
        }
        }

// startPlugin(String) 单个——传播异常
@Override
public void startPlugin(String pluginId) {
    if (plugins.get(pluginId) == null) throw new PluginLoadException("插件不存在: " + pluginId);
    startSinglePlugin(pluginId); // 异常自然传播
}
```

**行为变更说明**：现有调用 `startPlugin()` 而不捕获异常的代码，若插件 `onStart()` 抛出异常，现在会收到 `RuntimeException`
。这是 **正确**行为，签名不变，编译兼容。

---

### 1.2 `reloadConfig()` 并发窗口

**问题文件**：`sunsen-server/.../DefaultPluginContext.java`

**根因**：`configProperties.clear()` 与 `configProperties.load()` 之间存在时间窗口，并发读取线程会看到空配置。

**修复**：将 `configProperties` 字段改为 `volatile`，加载时构建新实例后原子替换；读方法加 `synchronized`：

```java
private volatile Properties configProperties = new Properties();

@Override
public synchronized String getProperty(String key) {
    return configProperties.getProperty(key);
}

@Override
public synchronized String getProperty(String key, String defaultValue) {
    return configProperties.getProperty(key, defaultValue);
}

@Override
public synchronized void reloadConfig() {
    Properties fresh = new Properties();
    loadInto(fresh);          // 新私有方法，从文件填充 Properties
    configProperties = fresh; // 原子替换
}

private void loadInto(Properties props) {
    Path configFile = pluginWorkDir.resolve("config.properties");
    if (Files.exists(configFile)) {
        try (InputStream is = Files.newInputStream(configFile)) {
            props.load(is);
        } catch (IOException e) {
            throw new PluginLoadException("Failed to load plugin config: " + configFile, e);
        }
    }
}
```

---

### 1.3 `close()` 不关闭 EventBus 的 Executor

**问题文件**：`sunsen-server/.../PluginEventBus.java`、`sunsen-server/.../DefaultPluginManager.java`

**根因**：`PluginEventBus` 持有可能是 `ExecutorService` 的 `Executor` 字段，但没有 `close()` 方法；`PluginManager` 默认
`close()` 不感知它的存在，导致异步事件总线场景下线程池泄漏。

**修复**：

1. `PluginEventBus` 添加 `close()` 方法：

```java
public void close() {
    if (executor instanceof ExecutorService es) {
        es.shutdown();
    }
}
```

2. `DefaultPluginManager` 覆盖 `close()`：

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

不修改 `PluginManager` 接口的默认 `close()` —— 默认实现维持原样，仅具体类补充。

---

### 1.4 `PluginManagerView.close()` 行为混乱

**问题文件**：`sunsen-server/.../DefaultPluginContext.java`（内部类 `PluginManagerView`）

**根因**：`PluginManagerView` 实现 `PluginManager`（进而 `AutoCloseable`），其默认 `close()` 调用 `stopPlugins()` 会抛
`PluginPermissionException`，行为令人困惑。

**修复**：在 `PluginManagerView` 内覆盖 `close()` 为 no-op：

```java

@Override
public void close() {
    // no-op: plugin cannot close the manager via PluginContext
}
```

---

## 阶段 2：API 一致性

### 2.1 `PluginContext` 缺少 `getExtension(extensionPoint, id)`

**问题文件**：`sunsen-core/.../PluginContext.java`、`sunsen-api/.../support/AbstractPlugin.java`

**根因**：`PluginManager` 有 `getExtension(Class<T>, String)` 精确按 ID 查找扩展，`PluginContext` 没有，不对称。

**修复**：在 `PluginContext` 接口添加 `default` 方法（非破坏性）：

```java
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

在 `AbstractPlugin` 同步添加便利方法：

```java
protected final <T> Optional<T> extension(Class<T> extensionPoint, String extensionId) {
    return context.getExtension(extensionPoint, extensionId);
}
```

---

### 2.2 `PluginWatcher` 强依赖具体类

**问题文件**：`sunsen-server/.../PluginWatcher.java`

**根因**：构造参数为 `DefaultPluginManager` 而非 `PluginManager` 接口，宿主无法替换实现。内部用到的
`manager.getPluginsDir()` 不在接口中。

**修复**：将插件目录作为独立参数，构造参数改为接口；旧构造函数标记 `@Deprecated` 保留迁移窗口：

```java
// 新构造函数
public PluginWatcher(PluginManager manager, Path pluginsDir) throws IOException { ...}

// 旧构造函数（向后兼容）
@Deprecated
public PluginWatcher(DefaultPluginManager manager) throws IOException {
    this(manager, manager.getPluginsDir());
}
```

---

### 2.3 `unloadPlugins()` 文档误导

**问题文件**：`sunsen-core/.../PluginManager.java`

**根因**：Javadoc 写"批量卸载所有 **STOPPED** 状态的插件"，但实现对 `ACTIVE` 插件先 stop 再 unload。文档是错的，实现是对的。

**修复**：仅修改 Javadoc：

```java
/**
 * 批量停止并卸载所有插件，释放 ClassLoader。
 * 对 ACTIVE 状态的插件先执行 stop 再 unload；
 * 对已 STOPPED 或 FAILED 的插件直接 unload。
 * 按拓扑逆序执行，确保被依赖的插件最后卸载。
 */
void unloadPlugins();
```

---

## 阶段 3：代码质量

### 3.1 `PluginManagerView` 用动态代理消除重复

**问题文件**：`sunsen-server/.../DefaultPluginContext.java`

**根因**：15+ 个几乎完全相同的 `throw new PluginPermissionException(...)` 方法，整个内部类约 130 行，真正有逻辑的仅约 10
个方法。

**修复**：删除 `PluginManagerView` 内部类，改用 `java.lang.reflect.Proxy`。定义允许通过的方法白名单，其余一律拒绝：

```java
private static final Set<String> ALLOWED_METHODS = Set.of(
        "getPlugin", "getPlugins", "getPlugins", "getPluginState", "isPluginActive",
        "getExtensions", "getExtension", "getExtensionInfos",
        "publishEvent", "subscribe", "unsubscribe", "close"
);

private PluginManager createManagerView() {
    return (PluginManager) Proxy.newProxyInstance(
            PluginManager.class.getClassLoader(),
            new Class[]{PluginManager.class},
            (proxy, method, args) -> {
                String name = method.getName();
                if (!ALLOWED_METHODS.contains(name)) {
                    throw new PluginPermissionException(
                            "Plugin cannot invoke " + name + " via PluginContext");
                }
                return switch (name) {
                    case "publishEvent" -> {
                        publishEvent((PluginEvent) args[0]);
                        yield null;
                    }
                    case "subscribe" -> {
                        subscribe((Class) args[0], (PluginEventListener) args[1]);
                        yield null;
                    }
                    case "unsubscribe" -> {
                        unsubscribe((Class) args[0], (PluginEventListener) args[1]);
                        yield null;
                    }
                    case "close" -> null; // no-op
                    default -> method.invoke(delegate, args);
                };
            }
    );
}
```

`getPluginManager()` 改为调用 `createManagerView()`。行为与现有 `PluginManagerView` 完全一致，代码量从 ~130 行减少到 ~35
行。

---

### 3.2 `AbstractPlugin.onDestroy()` 静默忽略关闭异常

**问题文件**：`sunsen-api/.../support/AbstractPlugin.java`

**根因**：`catch (Exception ignored)` 吞掉所有关闭异常，插件开发者无从知晓资源释放失败。

**修复**：改为 WARNING 日志，保持"不中断其他资源关闭"的行为：

```java
}catch(Exception e){
        System.

getLogger(getClass().

getName()).

log(
        System.Logger.Level.WARNING,
            () ->

pluginId() +": failed to close managed resource "
        +managedResources.

get(i).

getClass().

getName(),e);
        }
```

注意：`pluginId()` 在此处调用安全，因为 `context = null` 赋值发生在循环之后。

---

## 阶段 4：单元测试补全

所有测试类放在 `sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/` 下，使用 JUnit 5。

### `SemVerTest`

| 测试用例   | 输入                            | 预期                          |
|------------|---------------------------------|-------------------------------|
| 标准三段   | `"1.2.3"`                       | `SemVer(1,2,3)`               |
| v 前缀     | `"v2.0.0"`                      | `SemVer(2,0,0)`               |
| 两段版本   | `"1.0"`                         | `SemVer(1,0,0)`               |
| 预发布标记 | `"1.2.3-alpha"`                 | `SemVer(1,2,3)`               |
| 构建元数据 | `"1.0.0+build"`                 | `SemVer(1,0,0)`               |
| null 输入  | `null`                          | `IllegalArgumentException`    |
| 非数字     | `"abc"`                         | `IllegalArgumentException`    |
| 单数字     | `"1"`                           | `IllegalArgumentException`    |
| 比较顺序   | `1.0.0 < 1.0.1 < 1.1.0 < 2.0.0` | 各自 `compareTo` 返回负/零/正 |

### `VersionConstraintTest`

| 约束表达式       | 版本             | 匹配结果                   |
|------------------|------------------|----------------------------|
| `^1.2.0`         | `1.2.0`, `1.9.9` | true                       |
| `^1.2.0`         | `2.0.0`, `1.1.9` | false                      |
| `~1.2.0`         | `1.2.0`, `1.2.9` | true                       |
| `~1.2.0`         | `1.3.0`          | false                      |
| `>=1.0.0 <2.0.0` | `1.5.0`          | true                       |
| `>=1.0.0 <2.0.0` | `0.9.0`, `2.0.0` | false                      |
| `1.2.3`（精确）  | `1.2.3`          | true，其他 false           |
| `!=1.0.0`        | `1.0.0`          | false，其他 true           |
| 非法表达式       | `null`, 空串     | `IllegalArgumentException` |

### `PluginEventBusTest`

- 精确类型匹配：订阅 `PluginLoadedEvent`，发布 `PluginLoadedEvent` → 收到 1 次
- 父类型订阅：订阅 `PluginEvent`，分别发布 5 种内置事件 → 各收到 1 次
- 不匹配类型：订阅 `PluginLoadedEvent`，发布 `PluginStartedEvent` → 收到 0 次
- `unsubscribe` 后发布 → 收到 0 次
- `unsubscribeAll(pluginId)` → 该插件所有订阅被清理
- 监听器抛异常 → 不影响同一事件的其他监听器继续执行
- 同步模式 → 发布线程 == 分发线程（`Thread.currentThread()` 断言）

### `PluginDescriptorLoaderTest`

- 完整合法 JSON → 所有字段正确解析（id、name、version、mainClass、packagePrefixes、dependencies、permissions、vendorInfo）
- 缺少各必填字段（逐一）→ 各自抛 `PluginLoadException`
- `packagePrefixes` 为空数组 → 抛 `PluginLoadException`
- 前缀末尾带点（`"com.foo."`）→ 自动规范化为 `"com.foo"`
- `dependencies` 含重复 `id` → 抛 `PluginLoadException`
- 自依赖（`id` 与依赖 `id` 相同）→ 抛 `PluginLoadException`
- `optional: true` 正确映射到 `DependencyDescriptor.optional()`

### `ExtensionScannerTest`

- 直接实现 `@ExtensionPoint` 接口 → `inferExtensionPointTypes` 正确返回该接口
- 通过父类间接实现（继承链中有 `@ExtensionPoint` 接口）→ 也能推断到
- 仅实现未标注 `@ExtensionPoint` 的接口 → 返回空列表
- `allowMultiple=false`，同插件内两个实现 → `validateSingleExtensions` 抛 `PluginLoadException`
- `allowMultiple=true`，多实现 → 正常通过，不抛异常
- `allowMultiple=false`，仅一个实现 → 正常通过

---

## 修改文件清单

| 文件                                                | 修改类型                            |
|-----------------------------------------------------|-------------------------------------|
| `sunsen-server/.../DefaultPluginManager.java`       | 修复 Bug 1.1、1.3（覆盖 close）     |
| `sunsen-server/.../DefaultPluginContext.java`       | 修复 Bug 1.2、1.4；改进 3.1（代理） |
| `sunsen-server/.../PluginEventBus.java`             | 修复 Bug 1.3（添加 close）          |
| `sunsen-server/.../PluginWatcher.java`              | 改进 2.2（构造参数）                |
| `sunsen-core/.../PluginContext.java`                | 改进 2.1（default 方法）            |
| `sunsen-core/.../PluginManager.java`                | 改进 2.3（Javadoc）                 |
| `sunsen-api/.../support/AbstractPlugin.java`        | 改进 2.1（便利方法）、3.2（日志）   |
| `sunsen-server/.../SemVerTest.java`                 | 新增测试                            |
| `sunsen-server/.../VersionConstraintTest.java`      | 新增测试                            |
| `sunsen-server/.../PluginEventBusTest.java`         | 新增测试                            |
| `sunsen-server/.../PluginDescriptorLoaderTest.java` | 新增测试                            |
| `sunsen-server/.../ExtensionScannerTest.java`       | 新增测试                            |

**合计**：修改 7 个文件，新增 5 个测试文件。不新增外部依赖，不破坏公开 API。
