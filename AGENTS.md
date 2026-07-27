# AGENTS.md

> 本文件面向 AI 编码代理，描述 Sunsen 仓库的结构、构建方式、开发约定与关键架构不变量。
> 详细设计文档见 `readme.md`（架构与机制）和 `USAGE.md`（使用指南）；`CLAUDE.md` 内容与本文件基本一致。

## 项目概述

Sunsen 是一个轻量级 Java 插件框架（Java 21，Maven 多模块，groupId `com.bingbaihanji`，版本 `1.0-SNAPSHOT`）。
核心思路：每个插件由独立的 `PluginClassLoader` 隔离加载，插件元数据声明在 JAR 内的
`META-INF/plugin.json` 中，框架在加载阶段自动扫描 `@Extension` 并注册到扩展注册表，实现业务功能的动态插拔。
宿主可以是 Spring Boot、JavaFX 或纯 Java CLI 应用，框架本身不绑定任何容器。

## 模块结构

顶层 4 个模块（根 `pom.xml` 聚合），其中 `sunsen-demo-plain` 自身也是聚合模块：

| 模块 | 职责 | 依赖 |
|------|------|------|
| `sunsen-core/` | 公共契约：接口、注解、record、事件、异常（包 `com.bingbaihanji.sunsen.api.*`） | **零第三方依赖，仅 JDK** |
| `sunsen-api/` | 插件开发支持层：`AbstractPlugin`、`ManagedScheduler`、`PluginProperties`、`PluginCache`、`PluginFileSystem`，`@Plugin` 注解及编译期处理器 `PluginDescriptorProcessor`（自动生成 `META-INF/plugin.json`） | `sunsen-core` |
| `sunsen-server/` | 标准运行时实现：`DefaultPluginManager`、`PluginClassLoader`、`ExtensionRegistry`、`ExtensionScanner`、`PluginDescriptorLoader`、`DependencyResolver`、`PluginEventBus`、`PluginWatcher`、生命周期状态机、SemVer 版本约束 | `sunsen-core` + `jackson-databind`（仅此一个外部依赖） |
| `sunsen-demo-plain/` | 纯 Java 演示，聚合 `demo-api`（扩展点契约）、`demo-plugins`（hello/world/scheduler 三个插件）、`demo-host`（`PlainDemoApp`） | 见下 |

`sunsen-server` 源码在包 `com.bingbaihanji.sunsen.core.*` 下；测试与测试插件 fixture 在 `sunsen-server/src/test`。
`assets/` 存放 LOGO 等品牌资源。

**`{app}-api` 契约模式**：扩展点接口应抽成独立契约模块（如 `demo-api`），宿主和插件各自依赖它、互不依赖。
插件对 `sunsen-api` 和契约模块使用 `provided` 语义——运行时由宿主父 ClassLoader 提供。

## 构建、测试与运行命令

需要 JDK 21+。

```bash
# 全量构建并安装到本地仓库（必须先执行：子模块通过 GAV 互相引用）
mvn install

# 运行 sunsen-server 全部测试
mvn test -pl sunsen-server

# 单测试类 / 单方法（JUnit 5 + Surefire 3.2.5）
mvn test -pl sunsen-server -Dtest=DefaultPluginManagerIT
mvn test -pl sunsen-server -Dtest=HotReloadIT#methodName

# 运行纯 Java Demo（install 之后）
mvn exec:java -pl sunsen-demo-plain/demo-host
```

注意事项：

- **不要在 IDE 里直接 Run `PlainDemoApp`**：插件 JAR 由 `demo-plugins` 模块的 `maven-antrun-plugin` 在
  `prepare-package` 阶段编译打包到 `demo-plugins/target/plugins/`，没构建过就加载不到插件。
- `demo-host` 的 `exec-maven-plugin` 把工作目录设为 `sunsen-demo-plain/`，使相对路径
  `demo-plugins/target/plugins` 可用。
- 根 POM 在 `verify` 阶段用 `maven-source-plugin` 附加源码 JAR。

## 代码风格规范

仓库根目录有完整的《Java 开发规范.md》（类阿里规约），核心要点：

- 4 空格缩进（禁止 Tab），UTF-8 编码，Unix 换行，单行不超过 120 字符，文件尾保留一空行。
- 包前缀 `com.bingbaihanji.sunsen`；类名大驼峰，方法/变量小驼峰，常量全大写下划线分隔；抽象类以
  `Abstract`/`Base` 开头，异常类以 `Exception` 结尾。
- K&R 大括号风格；`if/for/while` 即使单条语句也必须加大括号；switch 必须有 `default`。
- 禁止通配符导入；禁止 `@author`/`@date` 缺失的类 Javadoc（类、属性、方法均需 Javadoc 注释）。
- 注释主要使用中文，说明"为什么"。
- 并发：`SimpleDateFormat` 不可共享；锁与解锁在 try-finally 中成对出现；线程须指定有意义名称。

## 测试约定

- 测试框架：JUnit 5（`junit-jupiter` 5.11.0），仅 `sunsen-server` 有测试；Surefire 包含 `**/*Test.java` 和
  `**/*IT.java`（`*IT` 为集成测试）。
- 测试插件 fixture 以 **源码形式** 存放在 `sunsen-server/src/test/resources/test-plugins/<name>/`（如
  `plugin-hello`、`plugin-world`、循环依赖与缺失依赖的反例等）；`TestPluginPackager` 在测试阶段现场编译打包为
  JAR 写入临时目录。**修改测试插件直接改 `.java` 源文件，不要提交编译产物 JAR**。

## 架构关键不变量（改动前必读）

这些约束分散在多个文件中，单看一处看不出来，违反它们会引发难定位的 bug：

1. **`sunsen-core` 零依赖**：所有跨 ClassLoader 边界的共享类型（`Plugin`、`PluginContext`、注解、事件）必须定义在
   `sunsen-core`，保证所有插件与宿主看到的是同一个 `Class` 对象。连 jackson 都不能引入。
2. **ClassLoader 加载顺序写死在 `PluginClassLoader.loadClass`**：`java.*`/`javax.*`/`sun.*` 和
   `com.bingbaihanji.sunsen.api.*` 强制走父加载器；命中插件 `packagePrefixes` 才自加载（破坏双亲委派，多前缀最长匹配）；
   再查依赖插件的 ClassLoader；最后兜底父加载器。`packagePrefixes` 加载前做冲突检测（保留前缀、其他插件前缀的包含关系），
   冲突直接拒绝加载。
3. **扩展点接口必须在宿主 classpath 上**（父加载器可见），不能出现在任何插件的 `packagePrefixes` 范围内。
4. **生命周期时序**：`@Extension` 扫描发生在 `LOADED` 阶段（`onInit()` 之后、`onStart()` 之前），因此第一个插件的
   `onStart()` 被调用时所有扩展均已注册。`onInit()` 里禁止调 `getExtensions()`、注册监听器或启动线程。任何改动生命周期
   顺序的代码都必须保留此不变量。
5. **状态机并发安全**：`LifecycleStateMachine` 用 `AtomicReference` CAS 推进状态，非法转换抛
   `IllegalStateTransitionException`。`FAILED` 是独立状态，只能通过 `unload()` 清理；插件生命周期方法的异常被捕获隔离，
   不影响其他插件。
6. **热重载原子窗口**：`reloadPlugin()` 在 `ExtensionRegistry` 写锁内完成"卸旧 → 注新"全过程，`getExtensions()` 持读锁，
   不存在"扩展数量为零"的中间窗口。**不要把注销和注册拆到两个独立的锁窗口**。被其他插件依赖的插件禁止热重载
   （抛 `PluginLoadException`）。
7. **apiVersion 闸门**：插件 `plugin.json` 的 `apiVersion` 只需 major 版本号与 `SunsenVersion.API_VERSION`（当前 `"1.1"`）一致即视为兼容（SemVer major 比较），minor/patch 差异不影响加载。公开 API 的破坏性变更须升 major 版本号。
   公开 API 的破坏性变更须同步升主版本号。
8. **权限网关**：JDK 21 已移除 `SecurityManager`，框架不依赖 JVM 沙箱。所有插件访问框架能力的入口收敛在
   `PluginContext`；宿主继承 `DefaultPluginContext` 在方法入口检查 `descriptor.permissions()` 实现拦截。新增暴露给插件的
   能力必须走 `PluginContext`，不要绕过。
9. **事件总线**：默认在发布者线程同步分发；异步需构造时注入 `Executor`。支持父类型订阅（订阅 `PluginEvent.class`
   收到所有生命周期事件）。通过 `PluginContext.subscribe()` 注册的监听器在插件 `UNLOADED` 时自动清理。
10. **`allowMultiple = false` 与 `sole = true`**：前者只校验同一插件内实现数不超过 1（跨插件允许多个）；后者保证全系统
    仅保留 `order` 最小的实现。两者正交，不要混淆语义。

## 主要功能面（便于定位代码）

- `PluginManager`（`sunsen-core`）：批量/单插件的 load/start/stop/unload、`reloadPlugin`（支持原地重载）、
  `installPlugin(Path)` / `uninstallPlugin(id)`（安装与卸载，含 `PluginInstalledEvent` / `PluginUninstalledEvent` /
  `PluginReloadedEvent`）、扩展查询（`getExtensions` / `getExtension` / `getExtensionInfos`）、事件订阅、
  `ExtensionRegistrar` 钩子、`loadPluginsWithResult()` 返回 `PluginLoadError` 列表。
- `PluginWatcher`（`sunsen-server`）：基于 `WatchService` 监听插件目录，新 JAR 自动加载启动、替换自动热重载、
  删除自动卸载，带防抖窗口，监听线程为 Daemon。
- `@Plugin` 注解 + `PluginDescriptorProcessor`（`sunsen-api`）：编译期自动生成 `META-INF/plugin.json`；
  若 `src/main/resources/META-INF/plugin.json` 已存在则以手动文件为准。`sunsen-api` 自身编译时通过
  `-proc:none` 禁用注解处理。

## 提交与 PR 约定

- 提交信息为简短祈使句，近期多为中文（如 `优化文档`、`修复 unloadPlugins() 对 STARTING 状态的处理`），
  也有英文约定式提交（`test: add ...`、`style: ...`）。主题聚焦行为变更。
- PR 应说明问题、实现方案、执行过的测试命令及兼容性影响；仅文档/资源变更才附截图。

## 安全注意事项

- 框架信任模型：插件代码与宿主同进程运行，无 JVM 级沙箱；权限控制依赖 `PluginContext` 网关拦截（见上文不变量 8），
  权限字符串（如 `file:read`）本身无硬编码语义，由宿主定义。
- 不要给 `sunsen-core` 添加任何第三方依赖；`sunsen-server` 新增依赖前先确认必要性。
- 仓库中无密钥/凭证类文件；提交前确认不引入编译产物（测试插件 JAR、`target/` 等）。
