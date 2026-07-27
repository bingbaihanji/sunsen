# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Sunsen 是一个轻量级 Java 插件框架。顶层有四个 Maven 模块（`sunsen-core` / `sunsen-api` / `sunsen-server` /
`sunsen-demo-plain`），其中 `sunsen-demo-plain` 是一个聚合模块，内含三个子模块：

| 子模块         | artifactId                  | 职责                                                              |
|----------------|-----------------------------|-------------------------------------------------------------------|
| `demo-api`     | `sunsen-demo-plain-api`     | 扩展点契约接口（`Greeter`、`GreetingFormatter`、`GreetingEvent`） |
| `demo-plugins` | `sunsen-demo-plain-plugins` | 三个演示插件（antrun 编译打包）                                   |
| `demo-host`    | `sunsen-demo-plain-host`    | 宿主应用（`PlainDemoApp`）                                        |

详细设计与使用文档见 `readme.md` 与 `USAGE.md`，本文件只记录跨文件才能理解的关键约束与常用命令。

## 常用命令

JDK 21+ 必需。`sunsen-core` 零第三方依赖（仅 JDK），所有跨 ClassLoader 的共享类型在此定义；`sunsen-server` 依赖
`jackson-databind` 解析 `plugin.json`。

```bash
# 全量构建（必须先 install，子模块通过 GAV 互相引用，IDE 直接跑会缺类）
mvn install

# 仅运行 sunsen-server 的集成测试
mvn test -pl sunsen-server

# 单测试类 / 单方法（JUnit 5，surefire 3.2.5）
mvn test -pl sunsen-server -Dtest=DefaultPluginManagerIT
mvn test -pl sunsen-server -Dtest=HotReloadIT#methodName

# 运行纯 Java Demo（先 install 才能拿到 plugins/*.jar）
mvn install
mvn exec:java -pl sunsen-demo-plain/demo-host
```

`demo-plugins` 子模块通过 `maven-antrun-plugin` 在 `prepare-package` 阶段从
`src/main/java/com/bingbaihanji/sunsen/demo/plain/{hello,world,scheduler}/` 分别编译（`includes` 模式隔离）， 打包到
`demo-plugins/target/plugins/`。 **不要在 IDE 里直接 Run `PlainDemoApp`**，会因为插件目录不存在而加载不到任何插件。

`sunsen-server` 测试同样不依赖外部 fixture：`TestPluginPackager` 在 `@BeforeAll` 阶段把
`src/test/resources/test-plugins/<name>/` 下的 `.java` + `plugin.json` 现场编译打包成 JAR 写入临时目录。改测试插件时直接编辑
`.java` 源文件即可，不要提交编译产物。

## 架构关键不变量

读源码前理解这些约束能省大量时间。它们分散在多个文件里，单看一处看不出来。

### 模块边界与依赖方向

- `sunsen-core`：只放接口、注解、record、异常。 **零依赖**（连 jackson 都不能引）。所有跨 ClassLoader
  边界的类型必须在这里定义，否则不同插件看到的不是同一个
  `Class` 对象。
- `sunsen-api`：插件开发模块，依赖 `sunsen-core`。提供插件开发所需的便利类和工具。
- `sunsen-server`：标准实现，依赖 `sunsen-core`，唯一外部依赖是 `jackson-databind`（仅用于 `PluginDescriptorLoader` 解析
  `plugin.json`）。新增依赖前先确认是否真的必要。
- 插件 JAR：依赖 `sunsen-api`（`provided` 语义——宿主已经提供）； **不要打包 `sunsen-core` 的类**，否则会和宿主父加载器里的同名类冲突。
- **扩展点接口必须放在宿主 classpath 上（父加载器可见），不能放在插件自己的 `packagePrefixes` 范围内**。生产项目
  应将扩展点接口单独抽成一个 `{app}-api` 模块，宿主和插件各自依赖它，互不依赖对方（`demo-api` 子模块即为此模式的示范）。

### ClassLoader 隔离规则（`PluginClassLoader`）

`loadClass` 顺序写死了，改之前一定要看 `PluginClassLoader.java`：

1. JDK 类（`java.*`/`javax.*`/`sun.*`）和 `com.bingbaihanji.sunsen.api.*` **强制走父加载器** —— 这是跨插件类型一致性的基础。
2. 命中 `plugin.json` 的 `packagePrefixes` → 自加载（破坏双亲委派）。多前缀按 **最长匹配**。
3. 依赖插件的类 → 通过依赖链查找。
4. 兜底委托父加载器。

`packagePrefixes` 在加载前会做冲突检测（保留前缀、其他插件前缀的包含关系），冲突直接拒绝加载。新增"宿主和插件共享类"
的需求时，类必须放在 `sunsen-core` 或宿主 classpath， **不能**放在某个插件的 `packagePrefixes` 范围内。

### 生命周期时序的核心约定

`@Extension` 扫描发生在 `LOADED` 阶段（`onInit()` 之后、`onStart()` 之前）—— 这意味着 **第一个插件的 `onStart()`
被调用时，所有插件的所有扩展都已注册到 `ExtensionRegistry`**。

实操约束：

- `onInit()` 里 **禁止**调 `getExtensions()` 或注册外部监听器、启动线程；这时其他插件可能还没 `LOADED`。
- `onStart()` 里才能安全访问扩展。
- 任何修改生命周期顺序的改动都必须保留这个不变量，否则插件互相依赖扩展时会触发难定位的时序 bug。

`LifecycleStateMachine` 用 `AtomicReference` CAS 推进状态，非法转换抛 `IllegalStateTransitionException`。`FAILED`
是独立终态（区别于 `STOPPED`），只能通过 `unload()` 清理。每个插件的生命周期方法异常都被捕获并隔离，不影响其他插件。

### 扩展点单例性与 `allowMultiple`

`@ExtensionPoint(allowMultiple = false)` 的校验在 `ExtensionScanner` 内 **按单个插件**做（不跨插件）——
即同一插件内不能有两个实现，但多个插件各自实现一个是允许的。如果业务真的要全局唯一，宿主侧拿到 `getExtensions()` 列表后自己做
size 校验。

### 热重载的原子窗口

`reloadPlugin()` 在 `ExtensionRegistry` 的写锁内完成"卸旧 → 注新"全过程，`getExtensions()` 是读锁。 **不存在"扩展数量为零"
的中间窗口**。修改 `ExtensionRegistry` 时要保持这个性质——任何把注销和注册拆成两个独立锁的尝试都会破坏热重载语义。

被其他插件依赖的插件 **禁止**热重载，框架会抛 `PluginLoadException`。需要先卸载依赖方。

### apiVersion 兼容性闸门

`SunsenVersion.API_VERSION`（`sunsen-core`，当前 `"1.1"`）和插件 `plugin.json` 的 `apiVersion` 只需 major 版本号一致即视为兼容（实现为
SemVer major 比较），minor/patch 差异不影响加载。框架公开 API 的破坏性变更必须同步升 `API_VERSION` 主号；新增方法之类的兼容变更不需要升版本号。

### 权限模型：`PluginContext` 网关

JDK 21 已移除 `SecurityManager`，框架不依赖任何 JVM 沙箱。 **所有插件访问框架能力的入口收敛在 `PluginContext` 接口**
（文件读写、扩展查询、事件发布）。宿主想做权限拦截就继承 `DefaultPluginContext` 在每个方法入口检查 `descriptor.permissions()`
。新增暴露给插件的能力时， **必须**走 `PluginContext`，不要绕过去（比如让插件直接拿 `PluginManager` 实例）。

### 事件总线线程模型

`PluginEventBus` 默认同步分发（发布者线程内）。异步通过构造时注入 `Executor`：
`new DefaultPluginManager(new PluginEventBus(executor))`。`PluginContext.subscribe()` 注册的监听器在插件 `UNLOADED` 时*
*自动清理**，插件不需要手动 `unsubscribe`。`PluginManager.subscribe()` 注册的宿主级监听器需要宿主自己管理生命周期。

事件总线支持父类订阅，订阅 `PluginEvent.class` 会收到所有内置生命周期事件（`PluginLoadedEvent` / `PluginStartedEvent` /
`PluginStoppedEvent` / `PluginUnloadedEvent` / `PluginFailedEvent`）。
