# Sunsen(桑生)插件系统

Sunsen 是一个轻量级的 Java 插件框架，名字取自"三生万物"的谐音，也暗合"桑葚"成簇共生的意象——主框架是枝干，插件是果实，各自独立又同枝共生。

核心思路很简单：通过独立的 ClassLoader 隔离插件，用 `plugin.json` 声明元数据，在加载阶段自动扫描并注册扩展，让业务功能可以动态插拔，不用硬编码到宿主里。

---

## 一、设计思想

### 1.1 从哪来的灵感

做这个框架的时候，我主要参考了三个东西，但都没有深度绑定：

| 参考来源 | 拿来了什么 | 怎么实现的 |
|---------|----------|-----------|
| **IntelliJ IDEA Platform** | 每个插件独立 ClassLoader，通过 Extension Point / Extension 解耦，`plugin.xml` 声明依赖 | 自定义 `PluginClassLoader` 打破双亲委派（只针对插件自有类），`plugin.json` 代替 XML，`@Extension` 自动扫描 |
| **Spring Boot** | 自动装配、生命周期事件 | 框架本身零第三方依赖（`sunsen-api` 只有 JDK），通过 `ExtensionRegistrar` 接口让宿主自行对接 Spring 等容器 |
| **OSGi / PF4J** | 依赖拓扑排序、循环依赖检测、热重载 | `DependencyResolver` 做 DAG 校验和 Kahn 拓扑排序，`reloadPlugin()` 做原子替换 |

### 1.2 几条铁律

- **契约优先**：扩展点必须是接口，插件只依赖接口，不依赖实现
- **类隔离**：插件自己的类由专属 ClassLoader 加载，公共 API（`sunsen-api`）走父加载器，避免依赖冲突
- **声明优于编码**：插件身份、依赖、扩展点全写在 `plugin.json` 里，不要硬编码
- **加载和启动分开**：`@Extension` 扫描在 `LOADED` 阶段完成，`onStart()` 里才能安全地 `getExtensions()`，杜绝时序 bug
- **不绑框架**：`sunsen-api` 和 `sunsen-core` 除了 `jackson-databind`（仅用来解析 `plugin.json`）之外没有任何第三方依赖
- **权限可拦截**：所有框架能力都收敛到 `PluginContext`，宿主可以继承 `DefaultPluginContext` 做权限网关

---

## 二、系统架构

### 2.1 分层

```
┌─────────────────────────────────────────────────────┐
│                  宿主应用 (Host App)                  │
│       Spring Boot / JavaFX / 纯 Java CLI 等          │
├─────────────────────────────────────────────────────┤
│              插件框架层 (sunsen-core)                 │
│  ┌──────────────┐  ┌───────────────────┐            │
│  │PluginManager │  │ ExtensionRegistry │            │
│  └──────────────┘  └───────────────────┘            │
│  ┌─────────────────┐  ┌──────────────────────┐      │
│  │PluginClassLoader│  │LifecycleStateMachine │      │
│  └─────────────────┘  └──────────────────────┘      │
│  ┌──────────────────┐  ┌──────────────────────┐     │
│  │DependencyResolver│  │    PluginEventBus     │     │
│  └──────────────────┘  └──────────────────────┘     │
├─────────────────────────────────────────────────────┤
│              公共 API 模块 (sunsen-api)               │
│  Plugin 接口 / 注解体系 / PluginContext / 事件契约    │
├─────────────────────────────────────────────────────┤
│                 插件实现 (Plugins)                    │
│    Plugin A     │    Plugin B    │    Plugin C       │
└─────────────────────────────────────────────────────┘
```

- **`sunsen-api`**：纯接口和注解，零外部依赖，宿主和所有插件都要依赖它，保证跨 ClassLoader 的类型一致性
- **`sunsen-core`**：标准实现，包括类加载器、扩展注册表、生命周期调度、依赖解析、事件总线。唯一的外部依赖是 `jackson-databind`（解析 `plugin.json`）
- **宿主应用**：直接用 `DefaultPluginManager`，或者继承它重写部分逻辑，把插件能力桥接到 Spring、JavaFX 或其他环境里

### 2.2 代码结构

```
sunsen/
├── sunsen-api/
│   ├── annotation/
│   │   ├── ExtensionPoint.java
│   │   └── Extension.java
│   ├── Plugin.java
│   ├── PluginContext.java
│   ├── PluginDescriptor.java          // record
│   ├── DependencyDescriptor.java      // record
│   ├── PluginManager.java             // interface
│   ├── PluginState.java
│   ├── ExtensionRegistrar.java
│   ├── SunsenVersion.java
│   ├── event/
│   │   ├── PluginEvent.java
│   │   ├── AbstractPluginEvent.java
│   │   ├── PluginEventListener.java
│   │   └── builtin/
│   │       ├── PluginLoadedEvent.java
│   │       ├── PluginStartedEvent.java
│   │       ├── PluginStoppedEvent.java
│   │       ├── PluginUnloadedEvent.java
│   │       └── PluginFailedEvent.java
│   └── ... (异常类)
└── sunsen-core/
    ├── DefaultPluginManager.java
    ├── DefaultPluginContext.java
    ├── PluginClassLoader.java
    ├── ExtensionRegistry.java
    ├── ExtensionScanner.java
    ├── PluginDescriptorLoader.java
    ├── DependencyResolver.java
    ├── PluginEventBus.java
    ├── lifecycle/
    │   └── LifecycleStateMachine.java
    └── version/
        ├── SemVer.java
        └── VersionConstraint.java
```

---

## 三、核心机制

### 3.1 类加载器隔离

每个插件有一个独立的 `PluginClassLoader`（继承 `URLClassLoader`），加载顺序如下：

1. **已加载的类**：直接返回
2. **公共 API 和 JDK**：`java.*`、`javax.*`、`sun.*`、`com.bingbaihanji.sunsen.api.*` 强制走父加载器，保证所有插件看到的 `Plugin` 接口是同一个 Class 对象
3. **插件自有类**：如果类名命中 `plugin.json` 里声明的 `packagePrefixes`，就打破双亲委派，自己加载。多个前缀时用**最长匹配**
4. **依赖插件的类**：按依赖顺序去依赖插件的 ClassLoader 里找
5. **兜底**：都找不到就委托父加载器

**前缀冲突检测**：加载前会校验所有插件的 `packagePrefixes`，如果和保留前缀（JDK、`sunsen-api`）或其他插件的前缀有包含关系，直接报错拒绝加载。

**卸载**：插件进入 `UNLOADED` 后，框架会调用 `classLoader.close()` 释放 JAR 句柄，然后解除强引用，等 GC 回收。

**热重载时的 ClassLoader 更新**：如果插件 A 依赖插件 B，B 热重载后，A 的 `PluginClassLoader` 里的依赖引用会自动更新到新版本。

### 3.2 扩展点与扩展

**扩展点**就是带 `@ExtensionPoint` 的普通 Java 接口：

```java
@ExtensionPoint(id = "task-handler", description = "处理业务任务的扩展点")
public interface TaskHandler {
    void handle(Task task);
}
```

**扩展**是带 `@Extension` 的实现类：

```java
@Extension(order = 10, description = "日志任务处理器")
public class LogTaskHandler implements TaskHandler {
    @Override
    public void handle(Task task) {
        System.out.println("Processing: " + task);
    }
}
```

**关键时序**：

框架在插件进入 `LOADED` 时（`onInit()` 之后、`onStart()` 之前）自动扫描 JAR 里所有 `@Extension` 类，注册到 `ExtensionRegistry`。这意味着**第一个插件的 `onStart()` 被调用时，所有插件的扩展都已经就绪**，彻底杜绝了在 `onStart()` 里注册扩展导致的时序依赖问题。

**`allowMultiple = false`**：如果扩展点设置了 `allowMultiple = false`，`ExtensionScanner` 会在同一插件内校验该扩展点的实现数量不能超过 1 个，否则加载失败。

宿主通过 `pluginManager.getExtensions(TaskHandler.class)` 获取按 `@Extension.order` 排好序的扩展实例列表。

### 3.3 生命周期状态机

```
               ┌─── JAR 扫描 + 描述文件解析 ───┐
               ▼                              │
           CREATED ──verify()──> RESOLVED ──load()──> LOADED
               │                    │            │
          (解析失败)            (依赖缺失)    (类加载/onInit失败)
               │                    │            │
               └────────────────────┴──> FAILED  │
                                                  │
                           STOPPED ◄──────────────┘
                           │    ▲     (首次加载等同于 STOPPED → start())
                        start() │ stop()
                           │    │
                           ▼    │
                        STARTING ──(onStart 异常)──> FAILED
                           │
                     (onStart 正常返回)
                           │
                           ▼
                         ACTIVE ──stop()──> STOPPING ──(onStop 异常)──> FAILED
                                                │
                                         (onStop 正常)
                                                │
                                                ▼
                                            STOPPED ──unload()──> UNLOADED(终态)
                                               │
                                           (热重载)
                                         start() 重新进入 STARTING


            FAILED ──unload()──> UNLOADED(强制清理)
```

| 状态 | 含义 |
|------|------|
| `CREATED` | JAR 被发现，`PluginDescriptor` 解析完成 |
| `RESOLVED` | 依赖校验通过，ClassLoader 已创建，主类还没实例化 |
| `LOADED` | 主类实例化完成，`onInit()` 已调用，`@Extension` 扫描完成 |
| `STARTING` | `onStart()` 执行中 |
| `ACTIVE` | 正常运行 |
| `STOPPING` | `onStop()` 执行中 |
| `STOPPED` | 已停止，可以 `start()` 重新启动 |
| `FAILED` | 异常终止，只能通过 `unload()` 清理 |
| `UNLOADED` | ClassLoader 已释放，扩展已注销，不可逆 |

**生命周期方法该干啥**：

- `onInit(PluginContext)`：注入上下文，做轻量初始化（读配置、准备数据结构）。**不要在这里启动后台线程或注册监听器**
- `onStart()`：启动业务逻辑、后台线程、注册外部监听器。这时扩展已经全部就绪，可以安全调用 `getExtensions()`
- `onStop()`：停止后台线程、取消监听器、释放网络连接等可以重新获取的资源
- `onDestroy()`：最终清理，释放所有强引用，确保 ClassLoader 能被 GC

**异常隔离**：`LifecycleStateMachine` 会捕获每个插件生命周期方法的异常，把该插件置为 `FAILED` 并发布 `PluginFailedEvent`，不影响其他插件。

**并发安全**：状态转换通过 `AtomicReference` 的 CAS 完成，非法转换会抛 `IllegalStateTransitionException`。

### 3.4 依赖解析

加载插件前，`DependencyResolver` 会执行以下步骤：

1. **存在性校验**：非 optional 的依赖必须能找到对应的插件
2. **版本约束校验**：支持 `>=1.0.0 <2.0.0`、`^1.2.0`、`~1.2.0`、`=1.0.0`、`>1.0.0`、`<2.0.0`、`!=1.0.0` 以及精确版本
3. **循环依赖检测**：DFS 检测环路，发现就抛 `CircularDependencyException`
4. **拓扑排序**：Kahn 算法生成加载和启动顺序，保证依赖方在后

### 3.5 插件描述文件

每个插件 JAR 的根目录下必须有 `META-INF/plugin.json`：

```json
{
  "id": "com.example.plugin.cleaner",
  "name": "Data Cleaner",
  "description": "提供数据清洗与格式转换能力",
  "version": "1.2.0",
  "apiVersion": "1.0",
  "vendor": {
    "name": "Example Corp",
    "url": "https://example.com"
  },
  "mainClass": "com.example.cleaner.CleanerPlugin",
  "packagePrefixes": [
    "com.example.cleaner"
  ],
  "dependencies": [
    {
      "id": "com.example.plugin.core",
      "version": ">=1.0.0 <2.0.0",
      "optional": false
    }
  ],
  "permissions": [
    "file:read",
    "file:write"
  ]
}
```

| 字段 | 必填 | 说明 |
|------|------|------|
| `id` | 是 | 全局唯一标识，建议反向域名 |
| `name` | 是 | 展示名称 |
| `description` | 否 | 插件功能描述 |
| `version` | 是 | SemVer 格式 |
| `apiVersion` | 是 | 编译时依赖的 Sunsen API 主版本号，必须和框架的 `SunsenVersion.API_VERSION` 一致 |
| `vendor` | 否 | 作者/厂商信息，解析为 `vendorInfo` Map |
| `mainClass` | 是 | 实现 `Plugin` 接口的入口类全限定名 |
| `packagePrefixes` | 是 | 插件私有包前缀，类隔离的核心配置 |
| `dependencies` | 否 | 依赖列表，支持版本约束和 optional 标记 |
| `permissions` | 否 | 运行时权限声明 |

---

## 四、注解体系

所有注解都在 `sunsen-api` 的 `annotation` 包下。

### `@ExtensionPoint`

```java
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExtensionPoint {
    /** 扩展点的稳定标识符，独立于接口全限定名 */
    String id() default "";

    /** 描述，给工具链或管理界面用 */
    String description() default "";

    /**
     * 是否允许多个扩展实现同时存在。
     * false 时框架会校验该扩展点实现数量恰好为 1，否则拒绝启动。
     */
    boolean allowMultiple() default true;
}
```

### `@Extension`

```java
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Extension {
    /** 扩展唯一标识，默认用实现类全限定名 */
    String id() default "";

    /** 排序权重，值越小越靠前，默认 0 */
    int order() default 0;

    /** 是否单例。true（默认）时框架只创建一个实例；false 时每次 getExtensions() 都新建 */
    boolean singleton() default true;

    /** 扩展描述 */
    String description() default "";
}
```

### 使用示例

```java
// 在 sunsen-api 或宿主中定义扩展点
@ExtensionPoint(id = "task-handler", description = "任务处理扩展点", allowMultiple = true)
public interface TaskHandler {
    void handle(Task task);
}

// 在插件 JAR 中实现扩展
@Extension(id = "log-task-handler", order = 10, description = "将任务记录到日志")
public class LogTaskHandler implements TaskHandler {
    @Override
    public void handle(Task task) {
        System.out.println("[LOG] Processing: " + task);
    }
}

@Extension(id = "db-task-handler", order = 20, description = "将任务持久化到数据库")
public class DbTaskHandler implements TaskHandler {
    @Override
    public void handle(Task task) {
        // 持久化逻辑
    }
}
```

宿主调用：

```java
// 按 order 升序，依次拿到 LogTaskHandler(10) 和 DbTaskHandler(20)
pluginManager.getExtensions(TaskHandler.class)
             .forEach(h -> h.handle(task));
```

---

## 五、顶层接口

### `PluginManager`

```java
public interface PluginManager {

    // 批量操作
    void loadPlugins();
    void startPlugins();
    void stopPlugins();
    void unloadPlugins();

    // 单插件操作
    PluginDescriptor loadPlugin(Path jarPath);
    void startPlugin(String pluginId);
    void stopPlugin(String pluginId);
    void unloadPlugin(String pluginId);
    void reloadPlugin(String pluginId, Path newJarPath);

    // 查询
    Optional<PluginDescriptor> getPlugin(String pluginId);
    List<PluginDescriptor> getPlugins();
    PluginState getPluginState(String pluginId);

    // 扩展访问
    <T> List<T> getExtensions(Class<T> extensionPoint);
    <T> Optional<T> getExtension(Class<T> extensionPoint, String extensionId);

    // 事件总线
    void publishEvent(PluginEvent event);
    <T extends PluginEvent> void subscribe(Class<T> eventType, PluginEventListener<T> listener);
    <T extends PluginEvent> void unsubscribe(Class<T> eventType, PluginEventListener<T> listener);

    // 钩子
    void setExtensionRegistrar(ExtensionRegistrar registrar);
}
```

### `PluginContext`

框架向每个插件注入一个 `PluginContext`，它是插件访问框架能力的唯一入口。宿主可以继承 `DefaultPluginContext`，在各方法入口做权限拦截。

```java
public interface PluginContext {
    PluginDescriptor getDescriptor();
    Path getPluginWorkDir();
    String getProperty(String key);
    String getProperty(String key, String defaultValue);
    <T> List<T> getExtensions(Class<T> extensionPoint);
    void publishEvent(PluginEvent event);
    <T extends PluginEvent> void subscribe(Class<T> eventType, PluginEventListener<T> listener);
    <T extends PluginEvent> void unsubscribe(Class<T> eventType, PluginEventListener<T> listener);
    PluginManager getPluginManager();
}
```

### `ExtensionRegistrar`

框架创建和销毁扩展实例时触发这个钩子，用来把扩展生命周期同步到宿主容器（比如 Spring 的 BeanFactory）。

```java
public interface ExtensionRegistrar {
    default void afterExtensionCreated(Object extensionInstance, Class<?> extensionPointType) {
    }

    default void afterExtensionDestroyed(Object extensionInstance, Class<?> extensionPointType) {
    }
}
```

### `PluginDescriptor`

对应 `plugin.json` 的内存模型，是一个 Java `record`，加载后不可变。所有集合字段都做了防御性复制（`List.copyOf` / `Set.copyOf` / `Map.copyOf`）。

```java
public record PluginDescriptor(
        String id,
        String name,
        String description,
        String version,
        String apiVersion,
        String mainClass,
        List<String> packagePrefixes,
        List<DependencyDescriptor> dependencies,
        Set<String> permissions,
        Map<String, String> vendorInfo
) {}

public record DependencyDescriptor(String id, String versionConstraint, boolean optional) {}
```

**相等性语义**：`PluginDescriptor.equals()` 只比较 `id` 和 `version`，方便同一插件不同版本的比较和替换。

---

## 六、事件系统

### 核心接口

```java
public interface PluginEvent {
    String getSourcePluginId();
    long getTimestamp();
}

@FunctionalInterface
public interface PluginEventListener<T extends PluginEvent> {
    void onEvent(T event);
}
```

### 内置生命周期事件

| 事件类 | 触发时机 |
|--------|---------|
| `PluginLoadedEvent` | 插件进入 `LOADED` 状态（`@Extension` 扫描完成后） |
| `PluginStartedEvent` | 插件进入 `ACTIVE` 状态 |
| `PluginStoppedEvent` | 插件进入 `STOPPED` 状态 |
| `PluginUnloadedEvent` | 插件进入 `UNLOADED` 状态（ClassLoader 释放前） |
| `PluginFailedEvent` | 插件进入 `FAILED` 状态，携带 `Throwable cause` |

### 订阅事件

**方式一：通过 `PluginContext` 订阅（推荐，框架自动管理生命周期）**

```java
public class MyPlugin implements Plugin {
    @Override
    public void onInit(PluginContext context) {
        context.subscribe(PluginStartedEvent.class, event ->
            System.out.println("Plugin started: " + event.getSourcePluginId())
        );
        context.subscribe(OrderCreatedEvent.class, this::handleOrderCreated);
    }
}
```

插件卸载时，框架会自动清理通过 `PluginContext` 注册的所有订阅，不用手动取消。

**方式二：通过 `PluginManager` 宿主级订阅**

```java
pluginManager.subscribe(PluginFailedEvent.class, event -> {
    log.error("Plugin {} failed: {}", event.getSourcePluginId(), event.getCause().getMessage());
    alertOncall(event);
});
```

**父类事件订阅**：事件总线支持订阅父类型。比如订阅 `PluginEvent.class` 就能收到所有生命周期事件。

### 自定义业务事件

插件之间可以通过事件总线解耦通信，不需要直接依赖对方的实现类：

```java
// 插件 A 定义事件（放在 sunsen-api 或独立的共享模块里）
public class OrderCreatedEvent implements PluginEvent {
    private final String sourcePluginId = "com.example.plugin.order";
    private final long timestamp = System.currentTimeMillis();
    private final Order order;
    // ...
}

// 插件 A 发布事件
context.publishEvent(new OrderCreatedEvent(order));

// 插件 B 订阅（在 onInit 里注册）
context.subscribe(OrderCreatedEvent.class, event -> {
    sendNotification(event.getOrder());
});
```

### 线程模型

`PluginEventBus` 默认在发布者线程同步分发（零开销，没有线程切换）。如果需要异步，可以在构造 `DefaultPluginManager` 时传入自定义 `Executor`：

```java
Executor executor = Executors.newCachedThreadPool();
DefaultPluginManager manager = new DefaultPluginManager(new PluginEventBus(executor));
```

---

## 七、热重载

### 设计目标

不重启宿主的情况下，把某个插件 JAR 替换为新版本，新扩展实例立即生效，旧对象无残留引用。

### 执行流程

调用 `pluginManager.reloadPlugin("com.example.plugin.cleaner", newJarPath)` 时，框架按以下顺序原子执行：

```
① 获取 ExtensionRegistry 写锁
② 调用 plugin.onStop()           → ACTIVE → STOPPING → STOPPED
③ 调用 plugin.onDestroy()        → STOPPED → UNLOADED
④ 注销该插件所有扩展
   └─ 对每个扩展实例调用 ExtensionRegistrar.afterExtensionDestroyed()
⑤ 取消该插件所有事件订阅
⑥ 释放旧 PluginClassLoader
⑦ 用新 JAR 创建新 PluginClassLoader
⑧ 解析新 plugin.json，校验 apiVersion 兼容性
⑨ 扫描新 JAR 的 @Extension，注册到 ExtensionRegistry
   └─ 对每个扩展实例调用 ExtensionRegistrar.afterExtensionCreated()
⑩ 实例化新 mainClass，调用 onInit()  → LOADED
⑪ 更新所有依赖该插件的其他插件的 ClassLoader 引用
⑫ 释放 ExtensionRegistry 写锁
⑬ 调用 plugin.onStart()            → ACTIVE
```

**并发安全**：步骤 ①–⑫ 持有写锁，`getExtensions()` 持有读锁。`ReadWriteLock` 保证读不阻塞，写操作原子完成，不存在"扩展数量为零"的中间窗口。

**失败处理**：如果步骤 ⑦–⑬ 任意一步失败，框架会把插件置为 `FAILED` 并发布 `PluginFailedEvent`。旧版本的扩展已经被注销了，宿主可以捕获事件后决定是否回退到旧 JAR。

**限制**：如果被其他插件依赖，`reloadPlugin()` 会直接抛 `PluginLoadException`。需要先卸载依赖方，或者避免给需要频繁热重载的插件建立依赖关系。

---

## 八、安全模型

### 背景

Java 17 废弃了 `SecurityManager`，Java 21 正式移除了。所以 Sunsen 不依赖 `SecurityManager`，而是采用**网关拦截模式**。

### 怎么拦截

插件访问所有框架能力（文件、配置、扩展、事件发布）的唯一通道是 `PluginContext`。宿主可以继承 `DefaultPluginContext`，在每个方法入口检查 `permissions`：

```java
public class SecurePluginContext extends DefaultPluginContext {

    @Override
    public Path getPluginWorkDir() {
        requirePermission("file:read");
        return super.getPluginWorkDir();
    }

    private void requirePermission(String permission) {
        if (!getDescriptor().getPermissions().contains(permission)) {
            throw new PluginPermissionException(
                getDescriptor().getId() + " 未声明所需权限: " + permission
            );
        }
    }
}
```

### 权限粒度（参考）

| 权限字符串 | 允许的操作 |
|-----------|-----------|
| `file:read` | 读取插件工作目录内的文件 |
| `file:write` | 写入插件工作目录内的文件 |
| `plugin:query` | 通过 `PluginContext` 查询其他插件状态 |
| `event:publish` | 发布自定义事件 |
| `extension:get` | 获取其他插件的扩展实例 |

权限字符串本身没有硬编码语义，框架只提供检查机制，宿主可以自己扩展。

---

## 九、逻辑流程

### 插件加载与启动完整流程

```
① 扫描 pluginsDir，收集所有 .jar 文件
② 逐个解析 META-INF/plugin.json，创建 PluginDescriptor，状态 → CREATED
③ DependencyResolver：
   a. 校验所有非 optional 依赖的存在性和版本约束
   b. DFS 检测循环依赖
   c. Kahn 算法拓扑排序，确定加载顺序
④ 按拓扑顺序，对每个插件：
   a. 创建 PluginClassLoader，状态 → RESOLVED
   b. 加载 mainClass，实例化插件对象
   c. 调用 plugin.onInit(pluginContext)
   d. 扫描 JAR 中所有 @Extension 类，注册到 ExtensionRegistry
      └─ 调用 ExtensionRegistrar.afterExtensionCreated()
   e. 状态 → LOADED；发布 PluginLoadedEvent
⑤ 所有插件 LOADED 后，按拓扑顺序调用 plugin.onStart()
   └─ 成功 → ACTIVE，发布 PluginStartedEvent
   └─ 失败 → FAILED，发布 PluginFailedEvent，继续下一个插件
⑥ 宿主通过 pluginManager.getExtensions(SomeAPI.class) 使用扩展
```

### 扩展点调用流程

```
宿主代码：
pluginManager.getExtensions(TaskHandler.class)
             .forEach(h -> h.handle(task));

ExtensionRegistry 内部：
1. 用 TaskHandler.class 的全限定名作为 key
2. 从 HashMap 取出对应的 ExtensionEntry 列表
3. 列表在注册时已按 @Extension.order 插入排序
4. 若 @Extension.singleton = true，返回缓存实例；否则 new 一个新实例
5. 持有 ReadLock，确保读取过程中不发生热重载
```

### 与 Spring Boot 集成示例

```java
@Configuration
public class SunsenConfig {

    @Bean
    public PluginManager pluginManager(ApplicationContext appContext) {
        DefaultPluginManager manager = new DefaultPluginManager();
        manager.setPluginsDir(Path.of("plugins"));

        // 把扩展实例同步注册为 Spring Bean
        manager.setExtensionRegistrar(new ExtensionRegistrar() {
            @Override
            public void afterExtensionCreated(Object instance, Class<?> epType) {
                ConfigurableApplicationContext ctx = (ConfigurableApplicationContext) appContext;
                ctx.getBeanFactory().registerSingleton(
                    instance.getClass().getName(), instance
                );
            }

            @Override
            public void afterExtensionDestroyed(Object instance, Class<?> epType) {
                ConfigurableListableBeanFactory factory =
                    ((ConfigurableApplicationContext) appContext).getBeanFactory();
                ((DefaultSingletonBeanRegistry) factory)
                    .destroySingleton(instance.getClass().getName());
            }
        });

        manager.loadPlugins();
        manager.startPlugins();
        return manager;
    }

    @Bean
    public List<TaskHandler> taskHandlers(PluginManager pm) {
        return pm.getExtensions(TaskHandler.class);
    }
}
```

### 与 JavaFX 集成示例

```java
PluginManager manager = new DefaultPluginManager();
manager.setExtensionRegistrar(new ExtensionRegistrar() {
    @Override
    public void afterExtensionCreated(Object instance, Class<?> epType) {
        if (instance instanceof ViewProvider vp) {
            Platform.runLater(() ->
                tabPane.getTabs().add(new Tab(vp.getTitle(), vp.createView()))
            );
        }
    }

    @Override
    public void afterExtensionDestroyed(Object instance, Class<?> epType) {
        if (instance instanceof ViewProvider vp) {
            Platform.runLater(() ->
                tabPane.getTabs().removeIf(t -> t.getContent() == vp.createView())
            );
        }
    }
});

manager.loadPlugins();
manager.startPlugins();

// 监听插件失败事件，在 UI 上展示告警
manager.subscribe(PluginFailedEvent.class, event ->
    Platform.runLater(() ->
        showAlert("插件异常", event.getSourcePluginId() + " 启动失败:" + event.getCause().getMessage())
    )
);
```

`ViewProvider` 是定义在 `sunsen-api` 中的扩展点接口，框架完全不感知 JavaFX 的存在。

---

## 十、关键设计决策

| 决策 | 为什么这么做 |
|------|-------------|
| 扩展注册在 `LOADED` 阶段（而不是 `onStart`） | 保证所有扩展在任何 `onStart()` 之前就已经可见，消除时序依赖 |
| 独立的 `FAILED` 状态 | 区分主动停止和异常终止，方便精准告警和排查 |
| `PluginContext` 作为权限网关 | Java 21 已经移除了 `SecurityManager`，这是当前最实际的沙箱手段 |
| `ExtensionRegistry` 读写锁 | 热重载期间扩展替换是原子的，读操作不会被阻塞 |
| `afterExtensionDestroyed` 钩子 | 热重载语义完整性的基础，防止宿主容器持有悬空引用导致内存泄漏 |
| 事件总线内置于框架 | 插件间解耦通信，避免直接类引用跨越 ClassLoader 边界 |
| `PluginDescriptor` 用 record | 不可变，天然线程安全，省掉一堆样板代码 |
| CAS 状态机 | 状态转换并发安全，不会出现竞态条件 |
