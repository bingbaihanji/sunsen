# Sunsen 质量深化 — 单元测试补全设计文档

**日期**：2026-06-08  
**版本**：1.0  
**范围**：PluginClassLoader 单测 + PluginWatcher 单测 + Manager 集成测试增强

---

## 背景与目标

经过第一轮改进（4 处 Bug 修复 + 3 处 API 一致性 + 2 处代码质量 + 5 个单元测试），项目已有 103 个测试。但以下核心组件仍缺乏测试覆盖：

- **PluginClassLoader**（0 测试）—— 框架类隔离的核心不变量，loadClass 顺序直接决定跨插件安全
- **PluginWatcher**（0 测试）—— 文件监听含防抖逻辑和并发处理，易出竞态 bug
- **DefaultPluginManager**（4 个集成测试）—— installPlugin/uninstallPlugin/reloadPlugin 回滚/并发加载等关键路径完全未覆盖

**约束**：不破坏公开 API，使用现有测试基础设施（TestPluginPackager、JUnit 5）。

---

## 阶段 1：Manager 集成测试增强（8 个场景）

**文件**：修改 `sunsen-server/src/test/java/.../DefaultPluginManagerIT.java`

复用已有夹具（`helloJar` / `worldJar` / `pluginsDir`），在原类中新增测试方法。

### 测试用例

| # | 测试方法 | 场景 | 验证点 |
|---|----------|------|--------|
| 1 | `testInstallPlugin` | 从外部路径复制 JAR → `installPlugin()` | JAR 已复制到插件目录，插件 ACTIVE，扩展可用 |
| 2 | `testInstallPluginSourceNotExist` | 源 JAR 不存在 → `installPlugin()` | 抛 `PluginLoadException`，无插件残留 |
| 3 | `testInstallRollbackOnStartFailure` | JAR 复制成功但 start 失败（JAR 中 onStart 抛异常） | JAR 文件被清理删除，`plugins` map 中无残留 |
| 4 | `testUninstallPlugin` | `loadPlugin → startPlugin → uninstallPlugin` | JAR 文件已删除，`getPlugin` 返回 empty |
| 5 | `testLoadPluginsWithResult` | 混合目录（1 个损坏 JAR + 2 个正常 JAR）→ `loadPluginsWithResult()` | 返回 List 包含 1 个 `PluginLoadError`，2 个正常插件 LOADED |
| 6 | `testReloadPluginWithRollback` | 准备旧版本 JAR → 用损坏 JAR 执行 `reloadPlugin` → 新 JAR 加载失败 | 旧版本插件恢复 ACTIVE，扩展来自旧版本 |
| 7 | `testConcurrentWaveLoading` | 两个无依赖插件（同一拓扑层）→ `loadPlugins` | 并行加载均成功 LOADED |
| 8 | `testStopAndRestartCycle` | ACTIVE → STOPPED → LOADED → restart → ACTIVE | 完整周期，扩展实例在重启后可正常使用 |

**所需新增测试夹具：**
- 场景 5：创建一个仅有无效 plugin.json（或缺少主类）的 ZIP 写入 `pluginsDir`
- 场景 6：创建一个"损坏版"JAR（matching plugin ID 但 mainClass 指向不存在类），触发 reloadPlugin 失败

---

## 阶段 2：PluginClassLoader 单元测试（10 个测试）

**文件**：新建 `sunsen-server/src/test/java/.../PluginClassLoaderTest.java`

### 测试夹具策略

构造最小的单类 JAR（只有一个 Hello.class），不依赖 plugin.json：
- 将测试用的 `.java` 源文件写入 `src/test/resources/`，用 `TestPluginPackager` 现场编译打包为 JAR
- 或用 `javax.tools.JavaCompiler` 直接在测试中编译 Java 源码字符串，用 `JarOutputStream` 打包（零外部文件依赖）

### 测试用例

| # | 测试方法 | 场景 | 验证点 |
|---|----------|------|--------|
| 1 | `testJavaLangDelegatesToParent` | 用 PluginClassLoader 加载 `java.lang.String` | 返回的 Class 来自父加载器 |
| 2 | `testApiPackageDelegatesToParent` | 加载 `com.bingbaihanji.sunsen.api.Plugin` | 走父加载器，不由插件自己加载 |
| 3 | `testOwnPrefixSelfLoads` | 加载插件自有包的类 | ClassLoader 是 PluginClassLoader 实例 |
| 4 | `testFallbackToParentForNonOwnClass` | 加载 Jackson 类（不在任何前缀） | 委托父加载器找到 |
| 5 | `testThrowsForMissingClassInOwnPrefix` | 命中前缀但 JAR 中不存在 | 直接 `ClassNotFoundException`，不穿透 |
| 6 | `testDependencyChainResolution` | 插 A 依赖插 B，A 访问 B 的类 | 通过依赖链找到 |
| 7 | `testUpdateDependencyLoaders` | `updateDependencyLoaders` 替换依赖列表 | 新依赖类可访问，旧依赖不可 |
| 8 | `testValidatePrefixesCrossPluginConflict` | 两插件声明 `com.foo` vs `com.foo.api` | 抛 `PluginLoadException` |
| 9 | `testValidatePrefixesSamePluginAllowsContainment` | 同一插件内 `com.foo` 和 `com.foo.api` | 通过不抛异常 |
| 10 | `testMatchesPackagePrefix` | 各边界输入：精确/前缀/不匹配 | 正确 true/false |

> 注：场景 8-9 中 `validatePrefixes` 和场景 10 中 `matchesPackagePrefix` 是 `public static` 方法，直接构造 `PluginDescriptor` record 实例即可测试，无需构建 JAR。

---

## 阶段 3：PluginWatcher 单元测试（6 个测试）

**文件**：新建 `sunsen-server/src/test/java/.../PluginWatcherTest.java`

### 测试夹具

- 创建临时目录作为插件目录
- 使用 `TestPluginPackager` 打包一个完整测试插件 JAR
- 每个测试独立构造 `DefaultPluginManager(pluginsDir)` 和 `PluginWatcher`
- `@AfterEach` 中 `watcher.close()` 清理监听器

### 测试用例

| # | 测试方法 | 场景 | 验证点 |
|---|----------|------|--------|
| 1 | `testAutoLoadNewJar` | `Files.copy` 写入新 JAR → sleep > `STABILIZE_DELAY_MS` | 插件被自动加载并启动，状态 ACTIVE |
| 2 | `testAutoReloadModifiedJar` | 先加载，再用新版本 JAR 覆盖（`REPLACE_EXISTING`） | 触发 reloadPlugin，新版本扩展生效 |
| 3 | `testAutoUnloadDeletedJar` | `Files.delete` 已加载的 JAR | 自动 stop + unload，状态 UNLOADED |
| 4 | `testDebounceMergesMultipleEvents` | 快速连续写入多次 `Files.copy` | 仅在最后一个事件超过防抖窗口后触发一次加载 |
| 5 | `testIgnoreNonJarFiles` | 创建 `.txt` `.tmp` `.log` 文件 | 不触发任何操作 |
| 6 | `testHandleCorruptedJar` | 写入 `new byte[]{0}` 作为 `.jar` 文件 | 不崩溃，日志记录 warning |

**注意点：**
- 场景 1-4 需要在写操作后 `Thread.sleep` 超过 `STABILIZE_DELAY_MS`（现在 500ms），考虑用 `Awaitility` 库轮询或更短的自定义值
- PluginWatcher 的 `STABILIZE_DELAY_MS` 是 private final，如果要用更短值需引入可配置性（范围外，直接用 sleep(1500) 确保安全余量）

---

## 修改文件清单

| 文件 | 操作 | 测试数 |
|------|------|--------|
| `DefaultPluginManagerIT.java` | 修改（新增方法） | +8 |
| `PluginClassLoaderTest.java` | 新建 | 10 |
| `PluginWatcherTest.java` | 新建 | 6 |

**合计**：修改 1 个文件，新增 2 个文件，24 个新测试。不引入新依赖。不修改生产代码（除非发现 bug 需要修复）。

---

## 执行顺序

按方案 B：**2 → 1 → 3**（从简到繁）

1. Manager 集成测试（8 个）—— 利用已有夹具，最快见效
2. PluginClassLoader 单测（10 个）—— 需要设计测试 JAR 构建策略
3. PluginWatcher 单测（6 个）—— 复杂度最高（文件系统+时间等待）
