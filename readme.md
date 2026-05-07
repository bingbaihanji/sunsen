# Sunsen(桑生)插件系统 技术设计文档

> **Sunsen(桑生)**,一款植根 Sun Java 生态而生的轻量化插件框架  
> 命名取谐音双关之意:承道家**三生万物**哲思,以单一核心基座为根,逐层衍生、无限扩展插件能力；  
> 音同**三生**、形取**桑葚**,以桑葚成簇、同枝共生之形态作为项目视觉意象,完美契合「主框架为枝干、各插件为硕果」的架构设计  
> 一核驭万端,让业务功能以插件形式自由生长、灵活组合,告别硬编码耦合,为 Java 应用提供极简、优雅、可无限拓展的插件化解决方案

---

## 一、设计思想

### 1.1 核心理念

Sunsen 框架通过**接口契约 + 类加载器隔离 + 声明式服务发现**,在不重启宿主应用的前提下,实现业务功能的动态插拔与按需装配

我们从主流技术栈中汲取精华,但不绑定任何具体框架:

| 借鉴来源                       | 借鉴思想                                                                                                      | 落地方式                                                                                                              |
|----------------------------|-----------------------------------------------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------|
| **IntelliJ IDEA Platform** | 每个插件独立 ClassLoader,通过扩展点(Extension Point)与扩展(Extension)实现解耦；`plugin.xml` 声明依赖与能力；插件在加载阶段完成扩展注册,启动阶段执行业务逻辑 | 自定义 `PluginClassLoader`,打破双亲委派仅限插件自有类；使用 `plugin.json` 声明式描述元数据；`@Extension` 扫描发生在 `LOADED` 阶段,与 `onStart()` 严格分离 |
| **Spring Boot**            | 自动装配、依赖注入、条件装配降低集成成本；完善的生命周期事件体系                                                                          | 框架本身零第三方依赖,通过顶层抽象接口(`PluginManager`、`ExtensionRegistrar`)让宿主自行对接任何 DI 容器；内置事件总线,支持生命周期监听                          |
| **OSGi / PF4J**            | 依赖拓扑排序与循环依赖检测；可选依赖；插件热重载                                                                                  | `DependencyResolver` 在加载前完成有向无环图校验与启动序计算；`reloadPlugin()` 提供原子性热替换语义                                              |

### 1.2 设计原则

- **契约优先**:所有扩展能力均抽象为接口,插件只依赖抽象而非具体实现
- **类隔离**:插件自有类由专用 ClassLoader 加载,公共 API 由父 ClassLoader 统一加载,杜绝依赖冲突
- **声明优于编码**:插件身份、依赖、扩展点均在 `plugin.json` 中描述,避免硬编码耦合
- **加载与启动分离**:扩展注册发生在 `LOADED` 阶段(自动扫描),`onStart()` 仅负责业务启动,确保扩展可见性在任何插件启动前即已确定
- **框架无关**:核心模块(`sunsen-api` 与 `sunsen-core`)不引入任何第三方框架依赖,通过顶层接口支持宿主自由适配
- **安全可控**:插件通过 `PluginContext` 访问所有框架能力,`PluginContext` 实现层统一做权限拦截,所需权限在描述文件中显式声明

---

## 二、系统架构

### 2.1 分层架构

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

- **sunsen-api**:纯接口与注解模块,同时被宿主和所有插件依赖,确保扩展契约在类加载器边界下的类型一致性零第三方依赖,仅依赖
  JDK
- **sunsen-core**:提供标准实现(类加载器、扩展注册表、生命周期调度、依赖解析、事件总线),依赖仅限于 `sunsen-api` 和 JDK
- **宿主应用**:直接使用 `DefaultPluginManager` 或继承并重写特定方法,将插件能力桥接到 Spring Bean 容器、JavaFX
  场景图或任何运行时环境

### 2.2 模块划分

```
sunsen/
├── sunsen-api/                     # 公共 API(零外部依赖)
│   ├── annotation/
│   │   ├── ExtensionPoint.java     # 扩展点标注
│   │   └── Extension.java          # 扩展实现标注
│   ├── Plugin.java                 # 插件生命周期接口
│   ├── PluginContext.java          # 插件运行时上下文
│   ├── PluginDescriptor.java       # 插件元数据模型
│   ├── PluginManager.java          # 插件管理器抽象类
│   ├── PluginState.java            # 插件状态枚举
│   ├── ExtensionRegistrar.java     # 扩展实例生命周期钩子
│   └── event/
│       ├── PluginEvent.java        # 事件基础接口
│       ├── PluginEventListener.java
│       └── builtin/                # 框架内置事件
│           ├── PluginLoadedEvent.java
│           ├── PluginStartedEvent.java
│           ├── PluginStoppedEvent.java
│           ├── PluginUnloadedEvent.java
│           └── PluginFailedEvent.java
└── sunsen-core/                    # 核心实现
    ├── DefaultPluginManager.java
    ├── DefaultPluginContext.java
    ├── PluginClassLoader.java
    ├── ExtensionRegistry.java
    ├── DependencyResolver.java
    ├── PluginEventBus.java
    └── lifecycle/
        └── LifecycleStateMachine.java
```

---

## 三、核心机制设计

### 3.1 类加载器隔离模型

每个插件持有独立的 `PluginClassLoader`(继承自 `URLClassLoader`),加载策略如下:

1. **已加载类**:直接返回,避免重复加载
2. **公共 API 与 JDK 类**:当类名属于 `sunsen-api` 包或 JDK 基础包(`java.*`、`javax.*`、`sun.*`)时,**强制委托给父
   ClassLoader**,确保所有插件的 `Plugin` 接口类为同一 `Class` 对象,保持类型系统一致性
3. **插件自有类**:若类名属于 `plugin.json` 声明的 `packagePrefixes`,**打破双亲委派**,由当前 ClassLoader 自行加载,实现私有化
4. **依赖插件可见性**:若当前 ClassLoader 找不到类,则按依赖顺序遍历依赖插件的 ClassLoader 进行查找,形成受控的类共享链
5. **兜底委托**:以上均未命中时,委托父 ClassLoader 加载(宿主 classpath)

**`packagePrefixes` 冲突检测**:框架在加载阶段校验所有插件的 `packagePrefixes`,若存在与 `sunsen-api` 包、JDK
包或其他已注册插件的前缀重叠,立即拒绝加载并报告错误

**ClassLoader 卸载**:插件进入 `UNLOADED` 状态后,框架解除对 `PluginClassLoader` 的所有强引用,使其可被 GC
回收,解决热重载场景下的类元空间泄漏

### 3.2 扩展点与扩展机制

**扩展点**(Extension Point):带有 `@ExtensionPoint` 注解的普通 Java 接口,定义契约

```java

@ExtensionPoint(id = "task-handler", description = "处理业务任务的扩展点")
public interface TaskHandler {
    /** 返回值越小优先级越高,供框架排序 */
    default int order() {
        return 0;
    }

    void handle(Task task);
}
```

**扩展**(Extension):使用 `@Extension` 注解标记的实现类

```java

@Extension(order = 10, description = "日志任务处理器")
public class LogTaskHandler implements TaskHandler {
    @Override
    public void handle(Task task) {
        System.out.println("Processing: " + task);
    }
}
```

**关键时序**:框架在插件进入 `LOADED` 状态时(`onInit()` 完成后、任何 `onStart()` 调用之前)自动扫描 JAR 中所有 `@Extension`
标注的类,并将其注册到 `ExtensionRegistry`这确保了当第一个插件的 `onStart()` 被调用时,**所有插件的扩展已全部可见**
,彻底避免了"在 `onStart()` 中注册扩展导致的时序依赖"问题

宿主或插件通过 `PluginManager.getExtensions(Class<T>)` 获取所有已按 `@Extension.order` 排序的扩展实例

### 3.3 插件生命周期状态机

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

**各状态说明**:

| 状态         | 含义                                                       |
|------------|----------------------------------------------------------|
| `CREATED`  | JAR 被发现,`PluginDescriptor` 解析完成,`PluginClassLoader` 尚未创建 |
| `RESOLVED` | 依赖关系校验通过,ClassLoader 已创建,但 `mainClass` 尚未实例化             |
| `LOADED`   | `mainClass` 实例化完成,`onInit()` 已调用,`@Extension` 扫描完成并注册    |
| `STARTING` | `onStart()` 调用中,尚未返回                                     |
| `ACTIVE`   | 正常运行                                                     |
| `STOPPING` | `onStop()` 调用中,尚未返回                                      |
| `STOPPED`  | 已停止,可调用 `start()` 重新启动(热重载的基础)                           |
| `FAILED`   | 异常终止,只能通过 `unload()` 强制清理                                |
| `UNLOADED` | ClassLoader 已释放,扩展已注销,此状态不可逆                             |

**生命周期方法职责**:

- `onInit(PluginContext)`:注入上下文,做轻量初始化(读取配置、准备数据结构)**禁止在此启动后台线程或注册监听器**
- `onStart()`:启动业务逻辑、后台线程、注册外部监听器此时扩展已全部就绪,可安全调用 `getExtensions()`
- `onStop()`:停止后台线程、取消外部监听器、释放网络连接等可重新获取的资源
- `onDestroy()`:ClassLoader 卸载前的最终清理,释放所有强引用,确保 GC 可回收

**异常隔离**:`LifecycleStateMachine` 捕获每个插件生命周期方法的异常,将该插件置为 `FAILED`,并发布 `PluginFailedEvent`
,不影响其他插件的状态流转

### 3.4 依赖解析

插件加载前,`DependencyResolver` 对所有 `PluginDescriptor` 执行以下步骤:

1. **存在性校验**:检查每个非 optional 依赖的 `id` 是否存在于已注册的插件集合中
2. **版本约束校验**:按语义化版本(SemVer)规则验证依赖版本是否满足约束表达式(支持 `>=1.0.0 <2.0.0`、`^1.2.0`、`~1.2.0`)
3. **循环依赖检测**:将依赖关系构建为有向图,执行 DFS 检测环路,发现环路时报告所有参与插件并拒绝加载
4. **拓扑排序**:对无环的依赖图执行 Kahn 算法,生成插件加载与启动的有序序列,确保依赖插件先于被依赖插件启动

### 3.5 插件描述文件

每个插件 JAR 中必须包含 `META-INF/plugin.json`:

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

**字段说明**:

| 字段                | 必填 | 说明                                 |
|-------------------|----|------------------------------------|
| `id`              | 是  | 全局唯一插件标识符,建议使用反向域名格式               |
| `name`            | 是  | 人类可读的展示名称                          |
| `description`     | 否  | 插件功能描述,供管理界面展示                     |
| `version`         | 是  | 插件版本,遵循 SemVer 规范                  |
| `apiVersion`      | 是  | 编译时依赖的 Sunsen API 主版本号,框架据此判断兼容性   |
| `vendor`          | 否  | 作者/厂商信息                            |
| `mainClass`       | 是  | 实现 `Plugin` 接口的入口类全限定名             |
| `packagePrefixes` | 是  | 由插件 ClassLoader 私有加载的包前缀,是类隔离的核心配置 |
| `dependencies`    | 否  | 依赖插件列表,支持版本约束与可选标记                 |
| `permissions`     | 否  | 插件所需权限,由 `PluginContext` 实现层在运行时检查 |

---

## 四、注解体系设计

所有注解位于 `sunsen-api` 的 `annotation` 子包

### 4.1 `@ExtensionPoint`

标注在接口上,声明该接口为一个可扩展的契约点

```java

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExtensionPoint {

    /**
     * 扩展点的稳定标识符,独立于接口全限定名,适合跨版本引用
     * 默认为空字符串,框架自动使用接口全限定名作为 key
     */
    String id() default "";

    /**
     * 扩展点描述,供工具链、管理界面或文档生成使用
     */
    String description() default "";

    /**
     * 是否允许多个扩展实现同时存在
     * false 时框架在所有插件加载完成后校验该扩展点实现数量恰好为 1,
     * 否则拒绝启动并报告冲突
     */
    boolean allowMultiple() default true;
}
```

### 4.2 `@Extension`

标注在实现类上,声明该类为某扩展点的一个实现框架在插件加载阶段自动扫描并注册

```java

@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Extension {

    /**
     * 该扩展的唯一标识,用于按名称精确获取单个扩展实例
     * 默认为空字符串,框架自动使用实现类全限定名作为 id
     */
    String id() default "";

    /**
     * 排序权重{@link PluginManager#getExtensions(Class)} 返回列表按此字段升序排列,
     * 值越小越靠前(优先级越高)默认为 0
     */
    int order() default 0;

    /**
     * 是否单例
     * true(默认):框架在注册时创建唯一实例,每次 getExtensions() 返回同一对象
     * false:每次 getExtensions() 调用时创建新实例(适用于有状态的扩展)
     */
    boolean singleton() default true;

    /**
     * 扩展描述,供工具链或管理界面展示
     */
    String description() default "";
}
```

### 4.3 使用示例

```java
// 在 sunsen-api 中定义扩展点
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

宿主调用:

```java
// 按 order 升序,依次得到 LogTaskHandler(10) 和 DbTaskHandler(20)
pluginManager.getExtensions(TaskHandler .class)
             .

forEach(h ->h.

handle(task));
```

---

## 五、顶层接口设计

所有接口均位于 `sunsen-api` 模块,`DefaultPluginManager` 等实现位于 `sunsen-core`

### 5.1 `PluginManager` 抽象类

```java
public abstract class PluginManager {

    /** 插件目录,使用 Path 类型以兼容现代 Java 文件 API */
    protected Path pluginsDir = Path.of("plugins");
    protected ClassLoader parentClassLoader = PluginManager.class.getClassLoader();

    // ── 批量操作 ──────────────────────────────────────────

    /** 扫描 pluginsDir,依赖拓扑排序后批量加载所有插件 */
    public abstract void loadPlugins();

    /** 按拓扑顺序批量启动所有 LOADED 状态的插件 */
    public abstract void startPlugins();

    /** 按拓扑逆序批量停止所有 ACTIVE 状态的插件 */
    public abstract void stopPlugins();

    /** 批量卸载所有 STOPPED 状态的插件,释放 ClassLoader */
    public abstract void unloadPlugins();

    // ── 单插件操作(热重载核心 API) ─────────────────────

    /** 从指定 JAR 路径加载单个插件,返回其描述符 */
    public abstract PluginDescriptor loadPlugin(Path jarPath);

    public abstract void startPlugin(String pluginId);

    public abstract void stopPlugin(String pluginId);

    public abstract void unloadPlugin(String pluginId);

    /** 原子性热替换:stop → unload → load(新 JAR)→ start */
    public abstract void reloadPlugin(String pluginId, Path newJarPath);

    // ── 查询 ─────────────────────────────────────────────
    public abstract Optional<PluginDescriptor> getPlugin(String pluginId);

    public abstract List<PluginDescriptor> getPlugins();

    public abstract PluginState getPluginState(String pluginId);

    // ── 扩展访问 ─────────────────────────────────────────

    /** 返回指定扩展点的所有实现,按 @Extension.order 升序排列 */
    public abstract <T> List<T> getExtensions(Class<T> extensionPoint);

    /** 按 id 精确获取单个扩展实例 */
    public abstract <T> Optional<T> getExtension(Class<T> extensionPoint, String extensionId);

    // ── 事件总线 ─────────────────────────────────────────
    public abstract void publishEvent(PluginEvent event);

    public abstract <T extends PluginEvent> void subscribe(
            Class<T> eventType, PluginEventListener<T> listener);

    public abstract <T extends PluginEvent> void unsubscribe(
            Class<T> eventType, PluginEventListener<T> listener);

    // ── 钩子配置 ─────────────────────────────────────────
    public abstract void setExtensionRegistrar(ExtensionRegistrar registrar);
}
```

### 5.2 `PluginContext` 接口

框架向每个插件注入一个 `PluginContext` 实例,作为插件访问框架能力的**唯一入口**,也是权限检查的执行层(
宿主自定义实现可在各方法内做权限拦截)

```java
public interface PluginContext {

    /** 获取当前插件的描述符(id、name、version 等元数据) */
    PluginDescriptor getDescriptor();

    /** 获取插件专属工作目录(框架保证目录存在且当前插件有读写权限) */
    Path getPluginWorkDir();

    /** 读取当前插件的配置项(来源可由宿主自定义,如配置文件、数据库、环境变量) */
    String getProperty(String key);

    String getProperty(String key, String defaultValue);

    /** 获取指定扩展点的所有实现(等同于 PluginManager.getExtensions,但作用域受权限约束) */
    <T> List<T> getExtensions(Class<T> extensionPoint);

    /** 发布事件至框架事件总线 */
    void publishEvent(PluginEvent event);

    /** 订阅事件插件在 onStop() 中无需手动取消订阅,框架在 UNLOADED 时自动清理 */
    <T extends PluginEvent> void subscribe(Class<T> eventType, PluginEventListener<T> listener);

    /** 获取 PluginManager,用于需要感知其他插件状态的高级场景 */
    PluginManager getPluginManager();
}
```

### 5.3 `ExtensionRegistrar` 接口(扩展实例生命周期钩子)

框架创建和销毁扩展实例时触发此接口,用于将框架的扩展生命周期同步到宿主容器

```java
public interface ExtensionRegistrar {

    /**
     * 扩展实例创建并注册到 ExtensionRegistry 后调用
     * Spring 宿主:在此将实例注册为 Bean 或完成 @Autowired 注入
     * JavaFX 宿主:在此将 UI 节点加入场景图
     */
    default void afterExtensionCreated(Object extensionInstance, Class<?> extensionPointType) {
    }

    /**
     * 扩展实例从 ExtensionRegistry 注销(插件 UNLOADED)前调用
     * Spring 宿主:在此从 ApplicationContext 移除 Bean
     * JavaFX 宿主:在此从场景图移除 UI 节点,取消数据绑定
     * 务必在此释放所有对 extensionInstance 的强引用,以便 GC 回收 ClassLoader
     */
    default void afterExtensionDestroyed(Object extensionInstance, Class<?> extensionPointType) {
    }
}
```

### 5.4 `PluginDescriptor` 模型

对应 `plugin.json` 的内存模型,加载后不可变(所有字段为 final)

```java
public final class PluginDescriptor {
    private final String id;
    private final String name;
    private final String description;
    private final String version;           // SemVer
    private final String apiVersion;        // Sunsen API 兼容主版本
    private final String mainClass;
    private final List<String> packagePrefixes;
    private final List<DependencyDescriptor> dependencies;
    private final Set<String> permissions;
    private final Map<String, String> vendorInfo; // name, url

    // 构造、getter 省略(全参构造 + static of(JsonObject) 工厂方法)
}

public final class DependencyDescriptor {
    private final String id;
    private final String versionConstraint; // e.g. ">=1.0.0 <2.0.0"
    private final boolean optional;
}
```

---

## 六、事件系统

### 6.1 核心接口

```java
/** 所有事件的基础接口 */
public interface PluginEvent {
    /** 触发此事件的插件 ID(框架内置事件为 "sunsen-core") */
    String getSourcePluginId();

    /** 事件发生时间戳(System.currentTimeMillis()) */
    long getTimestamp();
}

/** 类型安全的事件监听器 */
@FunctionalInterface
public interface PluginEventListener<T extends PluginEvent> {
    void onEvent(T event);
}
```

### 6.2 框架内置生命周期事件

框架在插件状态变迁时自动发布以下事件:

| 事件类                   | 触发时机                                  |
|-----------------------|---------------------------------------|
| `PluginLoadedEvent`   | 插件进入 `LOADED` 状态(`@Extension` 扫描完成后)  |
| `PluginStartedEvent`  | 插件进入 `ACTIVE` 状态                      |
| `PluginStoppedEvent`  | 插件进入 `STOPPED` 状态                     |
| `PluginUnloadedEvent` | 插件进入 `UNLOADED` 状态(ClassLoader 释放前)   |
| `PluginFailedEvent`   | 插件进入 `FAILED` 状态,携带 `Throwable cause` |

```java
// 内置事件示例
public class PluginFailedEvent implements PluginEvent {
    private final String sourcePluginId;
    private final long timestamp;
    private final PluginState stateAtFailure; // STARTING / STOPPING / LOADED 等
    private final Throwable cause;
    // ...
}
```

### 6.3 事件订阅方式

**方式一:通过 `PluginContext` 编程式订阅**(推荐,框架自动管理生命周期)

```java
public class MyPlugin implements Plugin {
    @Override
    public void onInit(PluginContext context) {
        // 订阅框架内置事件
        context.subscribe(PluginStartedEvent.class, event ->
                System.out.println("Plugin started: " + event.getSourcePluginId())
        );
        // 订阅自定义业务事件
        context.subscribe(OrderCreatedEvent.class, this::handleOrderCreated);
    }
}
```

插件进入 `UNLOADED` 状态时,框架自动取消该插件通过 `PluginContext` 注册的所有订阅,无需手动清理

**方式二:通过 `PluginManager` 宿主级订阅**(用于宿主监听插件生命周期)

```java
pluginManager.subscribe(PluginFailedEvent .class, event ->{
        log.

error("Plugin {} failed: {}",event.getSourcePluginId(),event.

getCause().

getMessage());

alertOncall(event);
});
```

### 6.4 自定义业务事件

插件间可通过事件总线解耦通信,无需直接依赖对方的实现类:

```java
// 插件 A 定义事件(位于 sunsen-api 或独立的共享模块)
public class OrderCreatedEvent implements PluginEvent {
    private final String sourcePluginId = "com.example.plugin.order";
    private final long timestamp = System.currentTimeMillis();
    private final Order order;
    // ...
}

// 插件 A 发布事件
context.

publishEvent(new OrderCreatedEvent(order));

// 插件 B 订阅(在 onInit 中注册)
        context.

subscribe(OrderCreatedEvent .class, event ->{

sendNotification(event.getOrder());
        });
```

**线程模型**:`PluginEventBus` 默认在发布者线程同步分发事件(zero-overhead,无额外线程切换)宿主可在构造
`DefaultPluginManager` 时传入自定义 `Executor`,切换为异步分发模式

---

## 七、热重载机制

### 7.1 设计目标

在宿主应用不重启的前提下,将某个插件 JAR 替换为新版本,新版本扩展实例对外立即可见,旧版本对象无残留引用

### 7.2 热重载流程

调用 `pluginManager.reloadPlugin("com.example.plugin.cleaner", newJarPath)` 时,框架按以下顺序原子执行:

```
① 获取 ExtensionRegistry 写锁
② 调用 plugin.onStop()           → 状态: ACTIVE → STOPPING → STOPPED
③ 调用 plugin.onDestroy()        → 状态: STOPPED → UNLOADED
④ 注销该插件所有扩展
   └─ 对每个扩展实例调用 ExtensionRegistrar.afterExtensionDestroyed()
⑤ 取消该插件所有事件订阅
⑥ 释放旧 PluginClassLoader(解除强引用,交由 GC 回收)
⑦ 用新 JAR 创建新 PluginClassLoader
⑧ 解析新 plugin.json,校验 apiVersion 兼容性
⑨ 扫描新 JAR 的 @Extension,注册到 ExtensionRegistry
   └─ 对每个扩展实例调用 ExtensionRegistrar.afterExtensionCreated()
⑩ 实例化新 mainClass,调用 onInit()  → 状态: LOADED
⑪ 释放 ExtensionRegistry 写锁
⑫ 调用 plugin.onStart()            → 状态: ACTIVE
```

**并发安全**:步骤 ①–⑪ 持有写锁,`getExtensions()` 持有读锁使用 `ReadWriteLock`
保证读操作无阻塞(读多写少场景),写操作原子完成,不存在"扩展数量为零"的中间窗口

**失败回滚**:若步骤 ⑦–⑫ 任意步骤失败,框架将插件置为 `FAILED` 并发布 `PluginFailedEvent`,旧版本扩展已被注销宿主可捕获
`PluginFailedEvent` 后决定是否回退到旧 JAR

---

## 八、安全模型

### 8.1 设计背景

Java 17 已废弃 `SecurityManager`,Java 21 已正式移除因此 Sunsen 不依赖 `SecurityManager` 实现权限控制,而是采用**网关拦截模式
**

### 8.2 网关拦截

插件访问所有框架能力(文件、配置、扩展、事件发布)的**唯一通道**是 `PluginContext`宿主提供的 `PluginContext` 实现(通常是
`DefaultPluginContext` 的子类)在每个方法入口处检查调用插件的 `permissions` 集合:

```java
public class SecurePluginContext extends DefaultPluginContext {

    private final Set<String> allowedPermissions;

    @Override
    public Path getPluginWorkDir() {
        requirePermission("file:read");
        return super.getPluginWorkDir();
    }

    private void requirePermission(String permission) {
        if (!allowedPermissions.contains(permission)) {
            throw new PluginPermissionException(
                    getDescriptor().getId() + " 未声明所需权限: " + permission
            );
        }
    }
}
```

### 8.3 权限粒度(参考规范)

| 权限字符串           | 允许的操作                       |
|-----------------|-----------------------------|
| `file:read`     | 读取插件工作目录内的文件                |
| `file:write`    | 写入插件工作目录内的文件                |
| `plugin:query`  | 通过 `PluginContext` 查询其他插件状态 |
| `event:publish` | 发布自定义事件                     |
| `extension:get` | 获取其他插件的扩展实例                 |

宿主可自由扩展权限字符串集合,框架不硬编码权限语义,只提供检查机制

---

## 九、逻辑流程说明

### 9.1 插件加载与启动完整流程

```
① 扫描 pluginsDir,收集所有 .jar 文件
② 逐个解析 META-INF/plugin.json,创建 PluginDescriptor,状态 → CREATED
③ DependencyResolver:
   a. 校验所有非 optional 依赖的存在性与版本约束
   b. 检测循环依赖(DFS)
   c. Kahn 算法拓扑排序,确定加载顺序
④ 按拓扑顺序,对每个插件:
   a. 创建 PluginClassLoader,状态 → RESOLVED
   b. 加载 mainClass,实例化插件对象
   c. 调用 plugin.onInit(pluginContext)
   d. 扫描 JAR 中所有 @Extension 类,注册到 ExtensionRegistry
      └─ 调用 ExtensionRegistrar.afterExtensionCreated()
   e. 状态 → LOADED；发布 PluginLoadedEvent
⑤ 所有插件 LOADED 后,按拓扑顺序调用 plugin.onStart()
   └─ 成功 → ACTIVE,发布 PluginStartedEvent
   └─ 失败 → FAILED,发布 PluginFailedEvent,继续下一个插件
⑥ 宿主通过 pluginManager.getExtensions(SomeAPI.class) 使用扩展
```

### 9.2 扩展点调用流程

```
宿主代码:
pluginManager.getExtensions(TaskHandler.class)
             .forEach(h -> h.handle(task));

ExtensionRegistry 内部:
1. 将 TaskHandler.class 的全限定名作为 key
2. 从 ConcurrentHashMap<String, List<ExtensionEntry>> 取出对应列表
3. 列表已按 @Extension.order 预排序(注册时插入有序位置)
4. 若 @Extension.singleton = true,返回缓存实例；否则创建新实例
5. 持有 ReadLock,确保读取过程中不发生热重载
```

### 9.3 与 Spring Boot 集成示例

```java

@Configuration
public class SunsenConfig {

    @Bean
    public PluginManager pluginManager(ApplicationContext appContext) {
        DefaultPluginManager manager = new DefaultPluginManager();
        manager.setPluginsDir(Path.of("plugins"));

        // 将扩展实例注册/注销同步到 Spring 容器
        manager.setExtensionRegistrar(new ExtensionRegistrar() {
            @Override
            public void afterExtensionCreated(Object instance, Class<?> epType) {
                // 通过 BeanDefinitionRegistry 动态注册 Bean
                ConfigurableApplicationContext ctx = (ConfigurableApplicationContext) appContext;
                ctx.getBeanFactory().registerSingleton(
                        instance.getClass().getName(), instance
                );
            }

            @Override
            public void afterExtensionDestroyed(Object instance, Class<?> epType) {
                ConfigurableListableBeanFactory factory =
                        ((ConfigurableApplicationContext) appContext).getBeanFactory();
                // 从单例注册表移除,解除 Spring 对实例的强引用
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

### 9.4 与 JavaFX 集成示例

```java
PluginManager manager = new DefaultPluginManager();
manager.

setExtensionRegistrar(new ExtensionRegistrar() {
    @Override
    public void afterExtensionCreated (Object instance, Class < ? > epType){
        if (instance instanceof ViewProvider vp) {
            Platform.runLater(() ->
                    tabPane.getTabs().add(new Tab(vp.getTitle(), vp.createView()))
            );
        }
    }

    @Override
    public void afterExtensionDestroyed (Object instance, Class < ? > epType){
        if (instance instanceof ViewProvider vp) {
            Platform.runLater(() ->
                    tabPane.getTabs().removeIf(t -> t.getContent() == vp.createView())
            );
        }
    }
});

        manager.

loadPlugins();
manager.

startPlugins();

// 监听插件失败事件,在 UI 上展示告警
manager.

subscribe(PluginFailedEvent .class, event ->
        Platform.

runLater(() ->

showAlert("插件异常",event.getSourcePluginId() +" 启动失败:"+event.

getCause().

getMessage())
        )
        );
```

`ViewProvider` 是定义在 `sunsen-api` 中的扩展点接口,框架完全不感知 JavaFX 的存在

---

## 十、总结

**Sunsen(桑生)** 以 IDEA 级别的类加载器隔离和扩展点机制为骨,以拓扑感知的依赖解析为脉,以原子性热重载和完整事件总线为翼,构成一套自洽的
Java 插件运行容器

**架构关键决策回顾**:

| 决策                         | 理由                                           |
|----------------------------|----------------------------------------------|
| 扩展注册在 LOADED 阶段(非 onStart) | 确保所有扩展在任何 onStart() 前可见,消除时序依赖               |
| 独立 FAILED 状态               | 区分主动停止与异常终止,支持精准告警与故障排查                      |
| PluginContext 作为权限网关       | 在 SecurityManager 已移除的 Java 21 环境下,唯一可行的沙箱手段 |
| ExtensionRegistry 读写锁      | 保证热重载期间扩展替换的原子性,读操作无阻塞                       |
| afterExtensionDestroyed 钩子 | 热重载语义完整性的基础,防止宿主容器持有悬空引用                     |
| 事件总线内置于框架                  | 插件间解耦通信,避免直接类引用跨越 ClassLoader 边界             |

无论是 Spring Boot 微服务、JavaFX 桌面程序,还是纯 Java 命令行工具,Sunsen
均能以「一核驭万端」之姿,承载您业务功能的无限生长与灵活装配正如三生衍万物、桑葚聚满枝——让插件开发回归简单,让系统架构臻于优雅
