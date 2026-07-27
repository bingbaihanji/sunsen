# Sunsen 插件框架 — 生产就绪优化升级方案

> 编制日期：2026-07-27
> 基准版本：1.0-SNAPSHOT（commit 86dbcbd）
> 输入：自审查报告（`docs/code-review-2026-07-27.md`）+ Kimi 审查报告（`../生产就绪审查报告.md`）+ 逐行代码交叉验证

---

## 前置说明：交叉验证结论

在合并两份报告前，对 Kimi 报告中的全部致命问题（C1-C7）及关键中等问题逐行验证。结果如下：

| Kimi 编号 | 问题描述 | 验证结果 | 备注 |
|-----------|----------|----------|------|
| C1 | 热重载原子窗口被拆分为两个锁窗口 | ✅ **确认** | `doUnloadSinglePlugin:844-851` 一次锁，`reloadPlugin:446-449` 另一次锁，中间存在间隙 |
| C2 | 卸载被依赖插件无防护 → 内存泄漏 | ✅ **确认** | `unloadPlugin/unloadPlugins/uninstallPlugin` 三条路径均无依赖者检查；reload Phase 4 因前置检查阻断而不可达 |
| C3 | 扩展注册失败无清理 → 孤儿扩展 | ✅ **确认** | `loadSinglePluginCore:775-784` catch 块不调用 `unregisterPlugin` |
| C4 | 批量加载不持 managementLock → 覆盖式 put 泄漏 ClassLoader | ✅ **确认** | 两个并发 `loadPlugins()` 同时通过 `containsKey` 检查，后到的 `put` 覆盖前者 |
| C5 | 权限网关没有接通 | ⚠️ **部分确认** | `sunsen-server` 主代码确实零引用 `permissions()`；但 `AbstractPlugin.requirePermission()` 存在且可用。问题是框架未做默认强制，需补文档/实现 |
| C6 | demo 构建 Windows-only | ✅ **确认** | `demo-plugins/pom.xml:73` 使用 `;` 拼接 classpath |
| C7 | 注解处理器三个 bug | ✅ **确认** | 三个 bug 均存在（非法前缀后继续生成、多 @Plugin 静默跳过、vendor 数组→单对象不一致） |
| — | `DependencyDescriptor` 缺 `hashCode` | ❌ **不成立** | Record 自动生成 `hashCode` 涵盖全部字段，与手动 `equals`（也涵盖全部字段）一致。手动 equals 是冗余但无 bug |
| — | `PluginDescriptor.equals` 与 `hashCode` 不一致 | ✅ **确认** | `equals` 只比 id+version，但 record 的 `hashCode` 涵盖全部 10 个组件。违反 Object 契约 |

Kimi 报告的 **C1-C7 七项致命问题中六项完全成立**（C5 是范围问题而非 bug），另有 15 项中等问题全部成立。我的自审查报告补充了 Kimi 未涉及的可观测性、优雅降级、架构设计等维度。

---

## 一、统一问题清单（去重合并后）

以下按严重程度和修复优先级排列。标记 `[K]` = Kimi 发现，`[C]` = Claude 发现，`[KC]` = 共同发现。

### P0 — 上线阻断（必须修复，共 8 项）

| 编号 | 来源 | 问题 | 影响 |
|------|------|------|------|
| **P0-1** | [K] C1 | 热重载"原子窗口"实际被拆成两个写锁窗口：`doUnloadSinglePlugin` 一次锁内注销 + `reloadPlugin` Phase 3 另一次锁内注册；中间 `getExtensions()` 可见空列表 | 热重载期间业务方拿到空扩展列表 → NPE |
| **P0-2** | [K] C2 | `unloadPlugin`/`unloadPlugins`/`uninstallPlugin` 不检查被依赖关系；被依赖插件卸载后依赖方 ClassLoader 中的 `dependencyLoaders` 仍持有已关闭 ClassLoader 的强引用 | ClassLoader 泄漏（Metaspace 永不可 GC）；依赖方卸载被依赖插件后新类加载静默失败 |
| **P0-3** | [K] C3 | `loadSinglePluginCore` 的 catch 块不调用 `extensionRegistry.unregisterPlugin()`；若 `ExtensionScanner.scan()` 中途某扩展注册失败，已注册的扩展永久残留 | `getExtensions()` 返回"死实例"（ClassLoader 已关闭）；sole 语义被污染 |
| **P0-4** | [K] C4 | `loadPluginsInternal` 主体在 `managementLock` 外执行，`plugins.put` 覆盖写入；`LifecycleStateMachine.init` 同样覆盖写入 | 并发 `loadPlugins()` → ClassLoader 泄漏；并发 load+unload → plugins/topology 失配 |
| **P0-5** | [K] C7 | 注解处理器三 bug：(a) packagePrefixes 校验失败后 `break` 但不 `continue`，仍产出 plugin.json；(b) `AtomicBoolean generated` 实例级，同模块两个 `@Plugin` 类→第二个静默跳过；(c) `vendor()` 数组被当作单对象生成 JSON | 编译告警但产出无效 plugin.json；多插件场景仅第一个生效；vendor 数据丢失 |
| **P0-6** | [K] C6 | `demo-plugins/pom.xml` 使用 `;` 拼接 classpath（Windows 专属）+ 直接引用 `../../sunsen-core/target/classes` 等物理路径 | Linux/macOS 无法构建；分阶段 CI 构建失败 |
| **P0-7** | [C] #7,#18 | `unloadPlugins` 对 `STARTING` 状态插件执行 `forceSet(STOPPED)` → `unloadSinglePlugin`，跳过 `onStop()`；`onStart()/onStop()/onDestroy()` 无超时保护 | 资源泄漏（网络连接、定时任务未释放）；插件挂起永久阻塞卸载 |
| **P0-8** | [KC] | `PluginDescriptor.equals()` 仅比较 id+version，但 record 的 `hashCode` 涵盖全部 10 个字段。两个 id+version 相同的 PluginDescriptor 在 HashSet/HashMap 中行为不可预测 | 同一插件不同配置版本的 descriptor 被 HashMap 互覆盖，数据静默丢失 |

### P1 — 生产必备（共 12 项）

| 编号 | 来源 | 问题 | 影响 |
|------|------|------|------|
| **P1-1** | [K] M2 | 生命周期回调只 `catch (Exception)`，`Error`（LinkageError/OOM）不转为 FAILED，状态机卡死；`doUnloadSinglePlugin` 的 onDestroy 遇 Error 跳过后续清理 | 状态永久残留；ClassLoader 不关闭 → 文件句柄泄漏 |
| **P1-2** | [K] M1 | 从不设置 TCCL 为插件 ClassLoader | 插件内 `ServiceLoader`、Jackson SPI 等拿到宿主 ClassLoader，找不到插件 SPI 实现 |
| **P1-3** | [K] M7 | `installPlugin` 中 `loadPlugin` 成功但 `startPlugin` 失败时只删 JAR，不卸载已加载的插件 | 内存残留已加载但启动失败的插件 |
| **P1-4** | [K] M3 | PluginWatcher 四件套：(a) OVERFLOW 直接 continue→事件丢失；(b) LOADED/RESOLVED/STARTING 状态 JAR 替换被静默吞掉；(c) `key.reset()` 返回值不检查；(d) `jarToPluginId` 快照覆盖不全 | 文件监控不可靠；手动加载的插件 JAR 删除后不自动卸载 |
| **P1-5** | [K] M4 | SemVer 三处偏离规范：(a) 预发布段被截断→`1.0.0-beta == 1.0.0`；(b) `^0.1.0` 展开为 `>=0.1.0 <1.0.0` 与 npm/cargo 惯例不符；(c) 第四段静默截断 | 版本约束语义错误：预发布版被当作正式版匹配；`^0.x` 过度放宽 |
| **P1-6** | [K] M5 | `PluginContext.getExtension()` 默认实现分两次独立调用 `getExtensionInfos` + `getExtensions`，热重载穿插时列表来自不同快照 | 返回错误的扩展实例或 IndexOutOfBounds |
| **P1-7** | [K] M6 | 插件 ID 无字符校验，`pluginsDir.resolve(pluginId)` 可被路径遍历利用 | `id: "../etc"` 可写入/读取插件目录外的文件 |
| **P1-8** | [K] M8 | sole + allowMultiple: 同插件对 sole 扩展点注册两个实现时，`ExtensionRegistry` 中的同插件检查放过 | sole 语义被破坏，两个实现共存 |
| **P1-9** | [K] M9 | 事件总线 `dispatch` 只 catch `Exception`，Error 传播给发布者；异步模式 executor 拒绝时 `RejectedExecutionException` 沿 publish 传播 | Error 中断后续监听器分发；拒绝异常误将发布者插件置 FAILED |
| **P1-10** | [K] M10 | 非单例扩展 `afterExtensionCreated` 回调不触发；`getExtensions()` 在读锁内反射实例化（慢操作阻塞写锁） | 非单例扩展的 DI/注册被跳过；热重载被非单例扩展的反射拖慢 |
| **P1-11** | [K] M11 | `PluginFileSystem` 路径越界校验用 `normalize().startsWith(workDir)`，不防符号链接逃逸 | 插件通过工作目录内符号链接读写外部文件 |
| **P1-12** | [C] #10 | `createManagerView()` 动态代理 + 硬编码方法名白名单 `ALLOWED_METHODS`——新增 PluginManager 方法时静默阻断 | 维护陷阱；性能开销；调试困难 |

### P2 — 质量加固（共 10 项）

| 编号 | 来源 | 问题 | 影响 |
|------|------|------|------|
| **P2-1** | [K] M12 | `startPlugin`/`stopPlugin` 单插件路径无 `managementLock` 保护，与并发 unload 交错时 CAS 失败暴露内部异常 | 用户看到 `IllegalStateTransitionException` 而非幂等降级 |
| **P2-2** | [K] M13 | `ManagedScheduler.create(pluginId, poolSize)` 两参便捷工厂不用插件 ThreadGroup，线程不受框架统一中断管控 | 插件卸载后线程残留运行 |
| **P2-3** | [K] M14 | 公开 API 空值策略不一致：`getPluginState` 返回 null vs 应返回 Optional；`getEnum` 缺 `Locale.ROOT`；`publishEvent` 误用 `PluginLoadException` 而非 `PluginPermissionException`；异常类缺 `(message, cause)` 构造器 |
| **P2-4** | [K] M15 | `PluginCache` 无容量上限/淘汰策略，`close()` 后仍可读写；`PluginEventBus.subscribe` 不去重；`ManagedScheduler.submit` 丢弃 Future；周期任务异常静默终止后续周期 |
| **P2-5** | [K] — | 死代码与注释失实：(a) reload Phase 4 不可达，(b) `DependencyResolver.detectCycles` boolean 返回值死代码，(c) `CREATED→LOADED` 状态转换边无调用方，(d) `ExtensionRegistry` 注释声称"缓存构造器"无缓存，(e) 注释称回调"在锁外"但 reload 路径持写锁 |
| **P2-6** | [C] #13 | `loadPluginsInternal` 虚拟线程加载中，`loadSinglePlugin` 内部已做 FAILED+事件发布，外层 catch 再次发布重复事件 |
| **P2-7** | [C] #14 | `DefaultPluginContext.getProperty()` 的 `synchronized` 不必要（Properties 内部同步+volatile 保证可见性），高频读取时有不必要锁开销 |
| **P2-8** | [C] #15 | 异常消息中英文混用（`startPlugin` 用中文，`unloadPlugin` 用英文） |
| **P2-9** | [C] #17 | `PluginDescriptorLoader` 不校验 `plugin.json` 中的未知字段→拼写错误静默忽略 |
| **P2-10** | [C] #20 | `PluginEventBus.close()` 不设关闭标记→同步模式下关闭后 publish 静默空执行，无日志 |

### P3 — 长期完善（共 8 项）

| 编号 | 来源 | 问题 |
|------|------|------|
| **P3-1** | [KC] E1-E2 | 测试缺口：(a) 热重载并发窗口测试；(b) ClassLoader 泄漏断言恢复；(c) 权限网关测试；(d) `sunsen-api` 零测试；(e) `PluginWatcherTest` 6 处裸 `Thread.sleep` → flaky；(f) `testAutoUnloadDeletedJar` 名不副实（watcher 已 close） |
| **P3-2** | [KC] E4 | 无 CI/CD：补 GitHub Actions（Windows+Linux 矩阵）；无 maven-enforcer-plugin；无 JaCoCo 覆盖率 |
| **P3-3** | [C] #5 | 缺少可观测性：插件加载耗时指标、扩展注册统计、事件分发计数、健康检查端点 |
| **P3-4** | [C] #11 | 缺少生命周期拦截器/过滤器链——超时检测、调用追踪等横切关注点无处插入 |
| **P3-5** | [K] P3 | demo 清理：(a) 硬编码个人绝对路径 `D:\bingbaihanji\Sunsen\...`；(b) `WorldPlugin` "小逼崽子"不当文字；(c) 1 小时循环 + watcher 立即关闭的演示逻辑不实；(d) `HelloPlugin` `static volatile` 配置传递反模式 |
| **P3-6** | [KC] | 文档与代码不一致：(a) CLAUDE.md/AGENTS.md 说 apiVersion"严格匹配"，代码是 major 比较；(b) readme.md PluginManager 接口定义过时；(c) 缺 plugin-schema.json |
| **P3-7** | [K] P3 | 发布元数据：根 POM 补 `<name>/<description>/<url>/<licenses>/<developers>/<scm>`；接入 japicmp/revapi 二进制兼容检查；jackson 升级 |
| **P3-8** | [C] #1,#24 | 接口职责过重：`PluginContext` 和 `PluginManager` 接口考虑拆分；1.0 正式发布前锁定接口 |

---

## 二、分阶段实施方案

### 第一阶段：修复致命缺陷（P0，预计 2-3 天）

**执行顺序按依赖关系排列：先局部后全局、先独立后关联**

#### Step 1: 修复 P0-5（注解处理器 bug）— 无依赖，独立修复

- **(a)** `PluginDescriptorProcessor.process()`：校验失败后补 `continue` 跳过生成
- **(b)** `AtomicBoolean generated` → 改为按 mainClass 去重，或检测到多 `@Plugin` 类时直接报编译错误
- **(c)** `Plugin.java` 的 `vendor()` 改为 `Vendor vendor() default @Vendor`；处理器对应适配
- 修改 `@Plugin` 注解 → 属公开 API 变更，需升 apiVersion 次版本号 → `1.1`

#### Step 2: 修复 P0-8（PluginDescriptor equals/hashCode 不一致）— 无依赖

- 方案 A：移除自定义 `equals`，用 record 默认行为（全部字段参与比较）——更符合"同一插件不同配置就是不同描述符"的语义
- 方案 B：同时覆写 `hashCode` 为 `Objects.hash(id, version)`
- **建议方案 A**：语义更正确。仅需在代码中依赖 id+version 比较的地方显式比较这两个字段

#### Step 3: 修复 P0-3（孤儿扩展）— 无依赖

- `loadSinglePluginCore` 的 catch 块中，在 `unsubscribeAll` 之前调用 `extensionRegistry.unregisterPlugin(pluginId)`
- 注意：此时可能还没有扩展注册（scan 之前就失败了），`unregisterPlugin` 应是幂等的（当前实现已满足）
- 补测试：构造一个插件，其第 N 个扩展注册时抛异常，断言失败后注册表中该插件无残留

#### Step 4: 修复 P0-1（原子窗口）+ P0-2（依赖者检查）— 强关联，一起改

这两个问题都涉及 `unload`/`reload` 路径的结构调整，改动范围重叠，建议一起做：

**重构 unload 路径：**
1. 将 `doUnloadSinglePlugin` 拆分为两部分：
   - `unloadOutsideLock(pluginId)`：onDestroy、threadGroup.interrupt()、unsubscribe（锁外）
   - `unregisterInsideLock(pluginId)`：ExtensionRegistry 注销 + 状态转换（锁内回调）
2. 抽取公共方法 `checkNoDependents(pluginId)` 供 `unloadPlugin`/`unloadPlugins`/`uninstallPlugin`/`reloadPlugin` 共用
3. 卸载成功后更新所有依赖者的 `dependencyLoaders`（把现 reload Phase 4 逻辑挪过来并修正）

**重构 reload 路径：**
4. 将 Phase 2 的注册表注销与 Phase 3 的注册合并到**同一次** `withWriteLock` 内
5. 回滚路径同样合并
6. 补测试：reload 期间并发循环 `getExtensions()`，断言任何时刻列表非空且不新旧混合

#### Step 5: 修复 P0-4（并发加载 ClassLoader 泄漏）— 依赖 Step 4

1. `loadPluginsInternal` 的加载主体（描述符解析之后的部分）在 `managementLock` 内执行
2. `loadSinglePluginCore` 的 `plugins.put` 改为 `putIfAbsent`，冲突时 close 新建 loader 并抛异常
3. `LifecycleStateMachine.init` 增加重复注册拒绝：`putIfAbsent` + 已存在则抛 `IllegalStateException`
4. 补真实并发测试：多线程同时 `loadPlugins()`

#### Step 6: 修复 P0-7（STARTING 状态强制卸载 + 超时）— 独立修复

1. `unloadPlugins`/`unloadPlugin` 对 `STARTING` 状态：先等待可配置超时（默认 5s），超时后中断线程再 `onDestroy`
2. 在 `executePhase` 和 `onDestroy` 调用中增加可配置超时（默认 30s），超时后中断线程
3. 超时值通过 `DefaultPluginManager` 的 setter 配置

#### Step 7: 修复 P0-6（跨平台构建）— 独立修复

1. `demo-plugins/pom.xml` 的 `;` → `${path.separator}`
2. 物理路径引用改为 `dependency:build-classpath` 或 `maven-dependency-plugin` 解析 GAV
3. `PlainDemoApp.java:151` 删除硬编码绝对路径

#### Step 8: 修复 P0-8 关联影响评估

`PluginDescriptor.equals` 变更会影响所有使用 `Set<PluginDescriptor>` / `Map<PluginDescriptor, ...>` 的代码。搜索并逐处评估。

---

### 第二阶段：生产必备修复（P1，预计 2-3 天）

| 顺序 | 编号 | 修复要点 |
|------|------|----------|
| 1 | P1-1 | `executePhase` + `doUnloadSinglePlugin` 改 `catch (Exception)` → `catch (Throwable)`，Error 时记录日志后继续流程 |
| 2 | P1-2 | `loadSinglePluginCore` 实例化前后 + `executePhase` 调用前后 set/restore TCCL；`getThreadFactory` 创建的线程 run() 内包装 TCCL |
| 3 | P1-3 | `installPlugin` 的 catch 中先 `unloadPlugin(descriptor.id())`（内存完整回滚），再删 JAR |
| 4 | P1-4 | (a) OVERFLOW→全目录重扫对账；(b) 补 LOADED/RESOLVED/STARTING 等状态分支；(c) `key.reset()==false`→记日志退出；(d) `jarToPluginId` 改为从 PluginManager 反查 |
| 5 | P1-5 | (a) 实现预发布比较（预发布 < 正式版）；(b) `^0.x` 锁定次版本 `<0.(x+1).0`；(c) 拒绝 >3 段版本号 |
| 6 | P1-6 | `PluginContext.getExtension` 默认实现下沉到 `ExtensionRegistry`，单次读锁内完成 |
| 7 | P1-7 | `PluginDescriptorLoader.parse` 对 id 加白名单校验 `^[a-zA-Z0-9][a-zA-Z0-9._-]*$` |
| 8 | P1-8 | `ExtensionRegistry.register` 对 sole 扩展点补充同插件内多次注册的检测 |
| 9 | P1-9 | `dispatch` 改 catch `Throwable`；`publish` 中 `executor.execute` 外 catch `RejectedExecutionException` 降级为 WARNING |
| 10 | P1-10 | `getExtensions` 改为锁内收集条目、锁外实例化非单例；非单例实例化后同样触发 `afterExtensionCreated` |
| 11 | P1-11 | `PluginFileSystem.resolve` 对已存在路径改用 `toRealPath()` 校验 |
| 12 | P1-12 | 用静态内部类（手动委托）替代 `createManagerView()` 动态代理 |

---

### 第三阶段：质量加固（P2，预计 2 天）

| 编号 | 修复要点 |
|------|----------|
| P2-1 | `startPlugin`/`stopPlugin` 在 `managementLock` 下执行，或 CAS 失败时幂等降级 |
| P2-2 | `ManagedScheduler.create(id, poolSize)` 默认通过 `AbstractPlugin.threadFactory()` 创建线程 |
| P2-3 | `getPluginState` 返回 `Optional<PluginState>` → 公开 API 变更，需升级次版本号；`getEnum` 加 `Locale.ROOT`；`publishEvent` 改用 `PluginPermissionException`；异常类补 `(message, cause)` 构造器 |
| P2-4 | `PluginCache` 加容量上限 + `close()` 后拒绝读写；`subscribe` 去重；`ManagedScheduler.submit` 返回 Future；Javadoc 补充周期任务异常处理说明 |
| P2-5 | 清理死代码；修正失实注释 |
| P2-6 | `loadPluginsInternal` 外层 catch 去重：检查状态机当前状态，避免重复 FAILED 事件 |
| P2-7 | 移除 `getProperty` 的不必要 `synchronized` |
| P2-8 | 统一异常消息为中文 |
| P2-9 | `PluginDescriptorLoader` 增加 strict mode 未知字段检测 |
| P2-10 | `PluginEventBus.close()` 增加 `closed` 标记，关闭后 `publish` 直接返回+WARNING |

---

### 第四阶段：长期完善（P3，预计 2-3 天）

| 编号 | 工作内容 |
|------|----------|
| P3-1 | 补齐全部测试缺口（见验收清单）；`PluginWatcherTest` 引入 Awaitility 替换裸 sleep；所有测试类加 `@Timeout` |
| P3-2 | 建立 GitHub Actions CI（Windows+Linux 矩阵）；接入 maven-enforcer-plugin（JDK 21+）；接入 JaCoCo（server 行覆盖率 ≥70%） |
| P3-3 | 在 `DefaultPluginManager` 中添加指标收集（加载耗时、状态分布、扩展数量）；`PluginManager` 接口增加 `healthCheck()` |
| P3-4 | 引入 `PluginLifecycleInterceptor` 接口，提供超时/日志拦截器默认实现 |
| P3-5 | demo 清理：删硬编码路径、删不当文字、重写为线性 main 流程、移除 static volatile 反模式 |
| P3-6 | 统一 apiVersion 文档口径（major 比较）；更新 API 文档；提供 `plugin-schema.json` |
| P3-7 | 补 POM 元数据；接入 japicmp；升级 jackson-databind |
| P3-8 | 评估 `PluginContext`/`PluginManager` 接口拆分方案，1.0 正式发布前决策 |

---

## 三、验收清单

### P0（上线阻断）— 全部完成后逐项验收

- [ ] P0-1: `reloadPlugin` 期间并发 `getExtensions()` 返回列表任何时刻非空，元素全为旧实例或全为新实例，无新旧混合
- [ ] P0-2: 有依赖者的插件执行 `unloadPlugin`/`uninstallPlugin` 抛 `PluginLoadException` 并提示依赖方 ID；`WeakReference` 泄漏断言通过（`ReferenceQueue` 轮询 + 超时）
- [ ] P0-3: 构造中途失败的插件 → 断言 `ExtensionRegistry` 中该插件扩展数为 0
- [ ] P0-4: 两个并发 `loadPlugins()` → 无 ClassLoader 泄漏（`putIfAbsent` 冲突路径有测试覆盖）
- [ ] P0-5: 处理器测试矩阵通过；(a) 非法前缀→编译错误且不产出 plugin.json；(b) 同模块两个 `@Plugin`→编译错误；(c) vendor 正常生成
- [ ] P0-6: `mvn install` 在 Linux 环境全绿
- [ ] P0-7: `onStart()` 死循环的插件在 30s 后转入 FAILED；`unloadPlugins` 对 STARTING 插件等待 5s 后强制卸载
- [ ] P0-8: `PluginDescriptor` 的 equals/hashCode 一致，`HashSet<PluginDescriptor>` 行为正确

### P1（生产必备）— 全部完成后逐项验收

- [ ] P1-1: `onStart()` 抛 `OutOfMemoryError` 的插件进入 FAILED 状态而非残留 STARTING
- [ ] P1-2: 插件内 `ServiceLoader.load(SomeSPI.class)` 能加载到插件 JAR 中的 SPI 实现
- [ ] P1-3: `installPlugin` 的 `startPlugin` 失败后，插件在内存中无残留
- [ ] P1-4: `PluginWatcher` OVERFLOW→全目录重扫；手动 `loadPlugin` 的 JAR 被删后正确卸载；`key.reset()==false` 时记日志退出
- [ ] P1-5: `1.0.0-beta` 不满足 `>=1.0.0`；`^0.1.0` 拒绝 `0.2.0`
- [ ] P1-6: reload 期间 `getExtension()` 返回一致的匹配结果
- [ ] P1-7: `plugin.json` 中 `"id": "../escape"` 被拒绝加载
- [ ] P1-8: 同插件对 sole 扩展点注册两个实现→第二个被拒绝并记 WARNING
- [ ] P1-9: 监听器抛 `Error`→后续监听器仍被调用；异步模式拒绝任务→WARNING 不抛异常
- [ ] P1-10: 非单例扩展获取实例时触发 `afterExtensionCreated` 回调；热重载不被非单例反射阻塞
- [ ] P1-11: 通过工作目录内符号链接访问外部文件被 `PluginFileSystem` 拒绝
- [ ] P1-12: 给 `PluginManager` 新增方法后，`PluginContext.getPluginManager()` 返回的视图正确代理（编译期保证）

### P2（质量加固）— 全部完成后逐项验收

- [ ] P2-1: `startPlugin` 与并发 `unloadPlugin` 交错时返回明确错误而非 `IllegalStateTransitionException`
- [ ] P2-2: `ManagedScheduler.create(id, 1)` 创建的线程归属于插件 ThreadGroup，卸载时被统一中断
- [ ] P2-3: `getPluginState` 返回 `Optional`（1.1 API 版本）；无其他空值策略不一致
- [ ] P2-4: `PluginCache` 满容量时拒绝写入；`PluginEventBus.subscribe` 重复订阅不重复分发
- [ ] P2-5: 死代码清除完毕；注释与代码行为一致
- [ ] P2-6: 插件加载失败时 `PluginFailedEvent` 仅发布一次
- [ ] P2-7: `getProperty` 无锁竞争
- [ ] P2-8: 所有异常消息统一为中文
- [ ] P2-9: 含未知字段的 `plugin.json` 在 strict mode 下输出 WARNING
- [ ] P2-10: 关闭后 `publish` 输出 WARNING

### P3（长期完善）— 逐步推进

- [ ] P3-1: CI 连续 10 次构建无 flaky 失败
- [ ] P3-2: CI 矩阵（Windows+Linux）绿；JaCoCo 覆盖率达标
- [ ] P3-3: `healthCheck()` 返回所有插件健康状态
- [ ] P3-5: demo 无个人路径、无不当文字、线性 main 流程可一次运行完成
- [ ] P3-6: CLAUDE.md / AGENTS.md / readme.md 中 apiVersion 描述统一为"major 版本一致"
- [ ] P3-7: `mvn install` 在干净环境（无本地仓库缓存）一次成功

---

## 四、修复顺序依赖图

```
P0-5 (处理器bug) ──→ P0-8 (equals/hashCode) ──→ 影响评估
P0-3 (孤儿扩展) ──────────────────────────────────────┐
                                                       ├─→ P0-4 (并发加载) ──→ P1-1 (Error处理)
P0-1 (原子窗口) ──→ P0-2 (依赖者检查) ──→ 重构完成 ──┘
P0-7 (超时+STARTING) ─────────────────────────────────┘
P0-6 (跨平台) ──────────────────── 独立 ───────────────→ P3-2 (CI)
```

**建议开工顺序**：P0-5 → P0-3 → P0-8 → (P0-1 + P0-2 联合) → P0-4 → P0-7 → P0-6 → P1 → P2 → P3

---

## 五、风险评估

| 风险 | 等级 | 缓解措施 |
|------|------|----------|
| P0-8 equals/hashCode 修复后破坏现有代码 | 中 | 全局搜索 `PluginDescriptor` 在 `HashSet`/`HashMap` 中的使用，逐处评估 |
| P0-1+P0-2 重构涉及 unload/reload 核心路径 | 中 | 分步提交，每步跑全量测试；新逻辑与新测试同 PR |
| P2-3 `getPluginState` 改为 `Optional` 属公开 API 变更 | 低 | 在 1.1 版本中变更，兼容期保留 @Deprecated 老方法 |
| P3-2 CI 搭建初期可能暴露平台相关 flaky 测试 | 中 | 先修已知 flaky（P3-1），再接入 CI |

---

*本文档基于对 Sunsen 全部源码（sunsen-core、sunsen-api、sunsen-server、sunsen-demo-plain）的逐行审查及两份独立审查报告的交叉验证编写。*
