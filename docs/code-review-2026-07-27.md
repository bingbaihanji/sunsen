# Sunsen 插件框架 — 全面代码审查报告

> 审查日期：2026-07-27 | 审查范围：sunsen-core、sunsen-api、sunsen-server（含测试）| 版本：1.0-SNAPSHOT

---

## 一、总体评价

Sunsen 是一个设计思路清晰、架构合理的轻量级 Java 插件框架。核心机制（ClassLoader 隔离、扩展点/扩展模型、生命周期状态机、依赖解析）的实现质量较高，代码可读性好，注释充分。**整体上是一个"准生产级别"的项目**——核心路径正确且健壮，但在可观测性、优雅降级、安全纵深防御等方面与真正的生产级别还有差距。

| 维度 | 评分 | 说明 |
|------|------|------|
| 架构设计 | ★★★★☆ | 模块边界清晰，核心抽象合理 |
| 设计模式 | ★★★★☆ | 运用得当，少数过度设计 |
| 代码逻辑 | ★★★★☆ | 核心路径正确，边缘场景有少量风险 |
| 并发安全 | ★★★☆☆ | 多级锁策略复杂，存在潜在竞态窗口 |
| 可观测性 | ★★☆☆☆ | 缺少指标、健康检查、超时机制 |
| 安全防护 | ★★☆☆☆ | PluginContext 网关是好的起点，但纵深不足 |
| 测试覆盖 | ★★★☆☆ | 单测覆盖核心组件，集成测试场景偏少 |

---

## 二、架构设计审查

### 2.1 模块划分 — 优秀

```
sunsen-core (零依赖) → sunsen-api (开发便利层) → sunsen-server (运行时实现)
```

**值得肯定的设计：**

- **sunsen-core 零第三方依赖**：这是框架最关键的架构决策。所有跨 ClassLoader 的类型（`Plugin`、`PluginContext`、注解、事件）集中在此模块，保证了不同 ClassLoader 看到的类型一致性。这是插件框架正确性的基石。
- **{app}-api 契约模式**：将扩展点接口从宿主和插件中独立出来，形成三方都依赖的契约模块，是成熟的插件化架构实践。
- **sunsen-api 与 sunsen-core 分离**：`AbstractPlugin`、`ManagedScheduler`、`PluginProperties` 等便利类放在 `sunsen-api` 而非 `sunsen-core`，保持了 core 模块的零依赖纯度。

**优化建议：**

1. **`PluginContext` 接口过于庞大**。目前 `PluginContext` 包含了配置读取、扩展查询、事件发布/订阅、插件管理、线程工厂等职责，违反了接口隔离原则。建议拆分为：
   - `PluginContext`（基础：descriptor、workDir、config）
   - `ExtensionAccessor`（扩展查询）
   - `EventPublisher` / `EventSubscriber`（事件）

   当前设计虽不影响使用，但随着框架演进，接口膨胀会使得 mock 和测试越来越困难。

2. **`PluginManager` 同样职责过重**。将生命周期管理、扩展查询、事件总线、安装/卸载混在一个接口里。如果未来要支持远程管理或 REST API，拆分会更有利。建议参考 OSGi 的分层模型。

### 2.2 ClassLoader 隔离机制 — 优秀

`PluginClassLoader.loadClass` 的五步加载顺序设计严谨：

```
已加载类 → JDK/API 父委托 → 插件自有类(最长前缀) → 依赖链查找 → 父兜底
```

关键正确性保证：
- `com.bingbaihanji.sunsen.api.*` 强制父委托，保证类型一致性
- 最长前缀匹配避免 `com.foo` 误匹配 `com.foobar`
- 前缀冲突检测覆盖保留前缀、跨插件冲突两种场景
- `close()` 释放 JAR 句柄，UNLOADED 后解除强引用

**潜在风险：**

3. **`dependencyLoaders` 使用 `volatile` 但不保证原子性**。`updateDependencyLoaders()` 先构建新的 `List.copyOf()`，然后赋值给 `volatile` 字段。在 `loadClass` 方法中，对 `dependencyLoaders` 的迭代发生在 synchronized 块内（`getClassLoadingLock`），但 `volatile` 只保证可见性不保证原子性——如果 `updateDependencyLoaders` 和 `loadClass` 的迭代并发执行，迭代器可能看到不一致的列表状态。

   - **风险等级**：低（`List.copyOf()` 返回不可变副本，迭代器不会抛出 `ConcurrentModificationException`）
   - **建议**：使用 `AtomicReference<List<PluginClassLoader>>` 替代 `volatile List`，语义更明确

4. **类加载失败时的错误信息不包含排查线索**。当 `findClass` 抛出 `ClassNotFoundException` 时，错误信息只有类名，缺少"哪个 JAR、哪个 ClassLoader、当前依赖链"等排查上下文。生产环境排查类加载问题是常见痛点。

### 2.3 生命周期状态机 — 良好

`LifecycleStateMachine` 的设计整体合理：
- CAS 原子状态转换，并发安全
- `VALID_TRANSITIONS` 静态表集中管理合法转换
- `forceSet` 支持异常回退
- `FAILED` 作为独立终态，语义清晰
- `excutePhase` 封装异常捕获 → FAILED → 发布事件的模式

**问题：**

5. **`CREATED → LOADED` 是合法转换但实际从未使用**。状态转换表定义了 `CREATED → LOADED`，但在 `DefaultPluginManager.loadSinglePluginCore` 中实际上是 `CREATED → RESOLVED → LOADED`。这个"死边"要么删除，要么说明场景（如跳过 RESOLVED 的快速路径）。

6. **缺少状态转换的审计/监听钩子**。状态机内部直接调用 `eventBus.publish()`，耦合了事件发布和状态管理。建议改为回调接口（`StateTransitionListener`），使事件发布成为其中一个实现，方便测试和扩展。

7. **`STARTING` 状态下执行 `unloadPlugins` 存在竞态风险**。代码中通过 `forceSet(pluginId, STOPPED)` 强制中断，但此时 `onStart()` 可能正在另一个线程中执行业务逻辑（如打开网络连接、启动定时任务）。`forceSet` 后立即调用 `unloadSinglePlugin`，会调用 `onDestroy`，但 `onStop` 被跳过了——这意味着该插件从未被通知"停止"，资源可能泄漏。

   - **风险等级**：中
   - **建议**：增加超时等待机制：先等待 `STARTING → ACTIVE` 或 `FAILED` 最多 N 秒，超时后再强制终止。或者在 `unloadSinglePlugin` 中检测状态为 `STARTING` 时先尝试中断对应线程再调 `onDestroy`。

### 2.4 依赖解析 — 良好

`DependencyResolver` 实现了标准的 Kahn 拓扑排序 + DFS 循环检测：

- `resolve()`：顺序执行存在性校验 → 版本约束校验 → 循环检测
- `sort()`：拓扑排序，返回加载顺序
- `sortByLevels()`：分层拓扑排序，支持波次并行加载

**问题：**

8. **`resolve()` 和 `sort()` / `sortByLevels()` 之间存在数据竞争窗口**。`resolve()` 完成后、`sortByLevels()` 调用前，如果插件集合发生变化（如并发调用 `loadPlugin`），拓扑顺序可能失效。当前代码在 `loadPluginsInternal` 中通过先解析所有 JAR、再统一处理的模式规避了此问题，但 API 层面没有强制约束——外部调用者可能分开调用这两个方法。建议：`resolve()` 返回一个不可变的"解析结果"对象，`sortByLevels()` 只能从该对象构造。

9. **循环依赖错误信息包含完整环路**，这点很好。但建议同时输出依赖链（不只是环路节点），帮助开发者理解"为什么会形成环"。

---

## 三、设计模式审查

### 3.1 运用得当的模式

| 模式 | 应用位置 | 评价 |
|------|----------|------|
| **注册表模式** | `ExtensionRegistry` | 核心设计正确。读写锁 + 排序插入 + sole 竞争处理 |
| **状态模式** | `LifecycleStateMachine` | CAS 状态转换 + 合法转换表，简洁可靠 |
| **观察者模式** | `PluginEventBus` | 同步/异步可切换，父类型订阅，设计干净 |
| **模板方法** | `AbstractPlugin.onInit()` → `onInitialized()` | 子类不能覆盖 `onInit()`，保证上下文注入先于初始化 |
| **门面模式** | `PluginContext` | 收敛框架能力入口，为权限拦截提供切入点 |
| **工厂方法** | `ManagedScheduler.create()` | 多重重载，线程工厂注入，设计合理 |
| **策略模式** | `PluginManager` 接口 + `DefaultPluginManager` | 接口抽象清晰，未来可替换实现 |

### 3.2 过度设计或有问题的模式

10. **`DefaultPluginContext.createManagerView()` 动态代理 —— 过度设计**

```java
return (PluginManager) java.lang.reflect.Proxy.newProxyInstance(
    PluginManager.class.getClassLoader(),
    new Class<?>[]{PluginManager.class},
    (proxy, method, args) -> {
        String name = method.getName();
        if (!ALLOWED_METHODS.contains(name)) { throw ... }
        return switch (name) { ... };
    }
);
```

问题：
- **维护风险**：`ALLOWED_METHODS` 白名单是硬编码的字符串集合。每次给 `PluginManager` 接口添加方法，都必须记得更新这个集合，否则新方法会抛 `PluginPermissionException`。这是典型的"静默失败"陷阱。
- **性能开销**：反射代理 + switch 字符串匹配，在每次调用时都有开销。虽然对于插件管理操作（低频调用）影响不大，但如果扩展查询走这条路径（高频），会有可测量的性能下降。
- **调试困难**：堆栈中出现 `$Proxy` 和 `invoke` 反射帧，排查问题时增加噪声。

**建议**：改用静态内部类（手动委托）：
```java
private class PluginManagerView implements PluginManager {
    // 每个方法手动委托，编译期检查
}
```
代码量略多，但零运行时开销，编译期保证方法不漏加。

### 3.3 缺失的设计模式

11. **缺少拦截器/过滤器链模式**。生命周期方法（`onInit`、`onStart`、`onStop`、`onDestroy`）目前由状态机直接调用，没有拦截点。如果未来需要添加"插件调用超时检测"、"调用链追踪"、"性能埋点"等横切关注点，需要修改状态机代码。建议引入 `PluginLifecycleInterceptor` 接口：

```java
interface PluginLifecycleInterceptor {
    void beforePhase(String pluginId, PluginState phase);
    void afterPhase(String pluginId, PluginState phase, long elapsedMs, Throwable error);
}
```

---

## 四、代码逻辑审查

### 4.1 并发安全

12. **多层锁策略复杂度高**

当前存在三层并发控制：
- `LifecycleStateMachine` 的 `AtomicReference` CAS（单插件状态）
- `ExtensionRegistry` 的 `ReentrantReadWriteLock`（扩展注册表）
- `DefaultPluginManager` 的 `ReentrantLock managementLock`（插件集合 + topology）

在 `reloadPlugin` 中，执行顺序是：`managementLock` → `extensionRegistry.writeLock`。其他地方是否有反向加锁？经审查：**`DefaultPluginManager` 内部所有调用 `extensionRegistry.withWriteLock()` 的地方都在 `managementLock` 内部，锁顺序一致，不会死锁**。

但 `PluginContext.getExtensions()` 从外部调用时持有 `extensionRegistry.readLock`，如果此时有代码在 `managementLock` 内尝试获取写锁，会发生写锁等待。这是读写锁的标准行为，不是死锁。

**建议**：在类的 Javadoc 中明确记录锁顺序约定（`managementLock → extensionRegistry.writeLock`），防止未来修改者破坏。

13. **`loadPluginsInternal` 中虚拟线程的异常处理不完整**

```java
Thread t = Thread.ofVirtual().start(() -> {
    try {
        loadSinglePlugin(descriptor, jarPath);
    } catch (Exception e) {
        waveErrors.add(new PluginLoadError(descriptor.id(), jarPath, e));
        stateMachine.forceSet(descriptor.id(), PluginState.FAILED);
        eventBus.publish(new PluginFailedEvent(descriptor, PluginState.CREATED, e));
    }
});
```

- `loadSinglePlugin` 内部的异常已经处理（状态 → FAILED，ClassLoader.close()）。外层 catch 捕获的是 `loadSinglePlugin` 之外的异常（理论上有 `Thread.start()` 的 `RejectedExecutionException` 等，但实际上虚拟线程不会抛这个）。
- 层 catch 再次 `forceSet` + `publish`，如果 `loadSinglePlugin` 内部已经做了这些操作，就变成了重复的 FAILED 设置和重复事件发布。虽然 `forceSet` 是幂等的，但重复事件会让监听器收到两次 `PluginFailedEvent`。

**建议**：外层只捕获 `loadSinglePluginCore` 未捕获的异常类型，或添加状态检查避免重复事件。

14. **`DefaultPluginContext.getProperty()` 的 `synchronized` 是不必要的**

```java
public synchronized String getProperty(String key) {
    return configProperties.getProperty(key);
}
```

`Properties` 是 `Hashtable` 的子类，其读方法是线程安全的。`configProperties` 字段是 `volatile` 的，`reloadConfig()` 通过赋值新 `Properties` 对象来更新。`synchronized` 在这里的作用是防止两个 `reloadConfig()` 并发执行，但这个场景极罕见。而每次 `getProperty` 都加锁，在高频读取场景下有性能影响。

**建议**：移除 `getProperty` 方法的 `synchronized`（保留 `reloadConfig` 的 `synchronized`），依赖 `volatile` 的可见性保证。

### 4.2 边缘场景

15. **`startPlugin` 和 `stopPlugin` 错误信息使用中文**，而 `unloadPlugin` 使用英文。

```java
// startPlugin
throw new PluginLoadException("插件不存在: " + pluginId);
throw new PluginLoadException("插件状态不是 ACTIVE,无法停止: " + pluginId);

// unloadPlugin
throw new PluginLoadException("Plugin does not exist: " + pluginId);
```

**建议**：统一异常消息语言。鉴于代码注释和提交信息为中文，建议全部使用中文。

16. **`installPlugin` 失败时删除已复制的 JAR，但不处理删除失败**

```java
} catch (Exception e) {
    try {
        Files.deleteIfExists(dest);
    } catch (IOException ignored) {
        // 静默忽略
    }
    throw e;
}
```

如果 `deleteIfExists` 失败（如文件被占用），`pluginsDir` 中会留下一个无法加载的 JAR。下次扫描到此 JAR 时会尝试解析并失败。虽然不会导致系统崩溃，但是一种"垃圾残留"。

**建议**：至少记录 WARNING 日志，帮助运维发现残留文件。

17. **`PluginDescriptorLoader` 不验证未知字段**。如果 `plugin.json` 中有拼写错误的字段（如 `"packgePrefixes"` 而非 `"packagePrefixes"`），解析器会静默忽略，导致插件无类隔离保护。这是常见的配置错误。

**建议**：添加 strict mode 选项，在解析完成后检查 root 节点是否还有未消费的 key。或者在开发模式下输出 WARNING。

18. **没有插件加载超时机制**。如果 `onInit()` 或 `onStart()` 陷入死循环或阻塞等待外部资源，整个加载流程会永久挂起。虚拟线程不会被 JVM 强制中断。

**建议**：增加可配置的超时（如 `plugin.load.timeout.seconds=30`），超时后中断线程并将插件置为 `FAILED`。

19. **`unloadSinglePlugin` 调用 `entry.threadGroup().interrupt()` 但不确定线程是否响应中断**。如果插件创建的线程不检查 `Thread.interrupted()` 或不在可中断的阻塞调用上等待，`interrupt()` 不会终止线程。

**建议**：文档中明确说明插件线程必须正确处理中断。同时考虑在 `interrupt()` 之后增加一个短暂的 `join()` 等待，超时后记录 WARNING 但不阻止卸载继续。

### 4.3 资源管理

20. **`PluginEventBus.close()` 只关闭 `ExecutorService`，不清理监听器**。关闭后如果有代码尝试 `publish()`，事件会被提交到已关闭的 `ExecutorService` 并抛出 `RejectedExecutionException`（被 dispatcher 的 `try-catch` 捕获并记录）。但如果 executor 是同步的（`Runnable::run`），close 是空操作，不会有任何提示。

**建议**：`close()` 中添加状态标记 `closed = true`，`publish()` 在关闭后直接返回或记录 WARNING，避免静默丢失事件。

21. **`PluginClassLoader.close()` 只关闭 URLClassLoader 的资源，但依赖链中的类可能已缓存在 JVM 的元空间中**。虽然 `close()` 释放了 JAR 文件句柄（允许文件被删除），但已加载的类定义仍然在元空间中，由 GC 在 ClassLoader 实例被回收时释放。如果插件被频繁热重载（如开发期间），可能累积元空间压力。

**建议**：不是紧急问题（JDK 的类卸载机制会处理），但在生产环境中应监控元空间使用量。可以考虑在文档中建议使用 `-XX:MetaspaceSize` 和 `-XX:MaxMetaspaceSize`。

### 4.4 测试覆盖评估

| 组件 | 测试类 | 覆盖程度 |
|------|--------|----------|
| `SemVer` / `VersionConstraint` | `SemVerTest`、`VersionConstraintTest` | ★★★★ 良好 |
| `PluginEventBus` | `PluginEventBusTest` | ★★★ 基本 |
| `DependencyResolver` | `DependencyResolverTest` | ★★★ 基本 |
| `ExtensionScanner` | `ExtensionScannerTest` | ★★★ 基本 |
| `ExtensionRegistry` | `ExtensionRegistryTest` | ★★★★ 良好 |
| `LifecycleStateMachine` | `LifecycleStateMachineTest` | ★★★★ 良好 |
| `PluginClassLoader` | `PluginClassLoaderTest` | ★★★★ 良好 |
| `PluginDescriptorLoader` | `PluginDescriptorLoaderTest` | ★★★ 基本 |
| `PluginWatcher` | `PluginWatcherTest` | ★★★ 基本 |
| `DefaultPluginManager` | `DefaultPluginManagerIT` | ★★★ 基本 |
| `HotReloadIT` | `HotReloadIT` | ★★☆ 覆盖关键路径 |
| `AbstractPlugin` | 无 | ☆☆☆☆ 缺失 |
| `PluginProperties` | 无 | ☆☆☆☆ 缺失 |
| `PluginCache` | 无 | ☆☆☆☆ 缺失 |
| `ManagedScheduler` | 无 | ☆☆☆☆ 缺失 |
| `PluginFileSystem` | 无 | ☆☆☆☆ 缺失 |
| `DefaultPluginContext` | 无 | ☆☆☆☆ 缺失 |

**测试方面的问题：**

22. **sunsen-api 模块完全没有测试**。`AbstractPlugin`（资源管理 LIFO 顺序）、`ManagedScheduler`（shutdown 幂等性）、`PluginProperties`（类型转换）、`PluginCache`（并发安全）、`PluginFileSystem`（路径越界检测）都需要测试。

23. **缺少并发压力测试**。没有测试验证高并发下 `getExtensions()` + `reloadPlugin()` 的正确性、虚拟线程并行加载的正确性、事件总线在高频发布下的行为。

---

## 五、生产级别差距分析

### 5.1 可观测性（当前状态：不足）

| 缺失能力 | 影响 | 优先级 |
|----------|------|--------|
| 插件加载耗时指标 | 无法感知性能退化 | 高 |
| 插件状态变更审计日志 | 故障回溯困难 | 高 |
| 扩展注册数量/类型统计 | 排查"扩展去哪了"困难 | 中 |
| 事件总线消息堆积量 | 异步模式下无法发现消费瓶颈 | 中 |
| JVM 集成（JMX/Micrometer） | 无法接入标准监控体系 | 中 |
| 健康检查端点 | 负载均衡/容器编排无法判断就绪状态 | 高 |

### 5.2 安全防护（当前状态：基本）

| 缺失能力 | 影响 | 优先级 |
|----------|------|--------|
| 插件 JAR 签名验证 | 无法防止恶意插件替换 | 中 |
| 插件资源配额（CPU/内存/线程） | 一个插件可能拖垮整个 JVM | 低（依赖 OS 级隔离） |
| `System.exit()` 防护 | 恶意/有 bug 的插件可终止整个进程 | 高 |
| 插件间数据隔离 | 插件可通过静态字段跨 ClassLoader 共享状态 | 低（Java 语言限制） |

关于 `System.exit()`：当前框架对此无防护。建议在 `DefaultPluginManager` 中安装一个 `SecurityManager` 的子类（即使在 JDK 21+ 中 `SecurityManager` 被标记为 deprecated for removal，但 `System.exit()` 的检查仍然有效。在 JDK 24+ 中才完全不可用）。

对于完全移除 SecurityManager 的高版本 JDK，可以考虑在插件文档中明确要求"插件不得调用 System.exit()"，并通过代码审查 / 插件审核流程保障。

### 5.3 优雅降级（当前状态：不足）

| 缺失能力 | 影响 | 优先级 |
|----------|------|--------|
| 插件加载超时 | 被卡住的插件永久阻塞加载流程 | 高 |
| 插件启动超时 | 同上 | 高 |
| 优雅关闭超时 | `onStop()` 挂起导致 JVM 无法退出 | 高 |
| 重试/退避策略 | 瞬时故障（如网络未就绪）导致永久加载失败 | 中 |
| 插件降级/回退策略 | 新版本加载失败后无自动回退到上一个可用版本 | 中 |

### 5.4 API 稳定性（当前状态：良好）

- 核心接口（`Plugin`、`PluginContext`、`PluginManager`）使用 default 方法，向后兼容性好
- `@ExtensionPoint` 和 `@Extension` 注解设计稳定
- `PluginDescriptor` 使用 record，不可变

**问题：**

24. **`PluginManager` 接口频繁变动**。最近的提交增加了 `installPlugin`、`uninstallPlugin`、`reloadPlugin(String)`、`getExtensionInfos` 等方法。每次增加方法都影响所有实现者。建议：在 1.0 正式发布前完成接口的 finalize；1.0 之后的新方法通过 default 方法或新的子接口添加。

### 5.5 配置与文档

25. **`plugin.json` 缺少 JSON Schema**。没有 Schema 意味着 IDE 无法提供自动补全和校验。建议提供一个 `plugin-schema.json` 文件。

26. **`readme.md` 中的 `PluginManager` 接口定义已过时**。缺少 `installPlugin`、`uninstallPlugin`、`reloadPlugin(String)`、`loadPluginsWithResult`、`getExtensionInfos`、`close()` 等方法。文档与实际代码不一致。

27. **`PluginDescriptorLoader` 中的 `apiVersion` 兼容性语义未对齐**。文档（`readme.md`）说"只需要 major 版本号一致即视为兼容"，但 `CLAUDE.md` 说"必须严格匹配 `SunsenVersion.API_VERSION`"。实际代码实现的是 major 版本比较。需要统一文档。

---

## 六、优化方案（按优先级排序）

### P0 — 上线前必须解决

1. **添加插件加载/启动超时机制**
   - 在 `loadSinglePluginCore` 和 `startSinglePlugin` 中增加可配置的超时（默认 30s）
   - 超时后中断执行线程，将插件置为 FAILED
   - 同时为 `onStop()` 添加关闭超时

2. **修复 `STARTING` 状态被强制卸载的资源泄漏风险**（问题 7）
   - 在 `unloadPlugins()` 中对 `STARTING` 状态增加等待窗口（如 5s）
   - 超时后先尝试调用 `onStop()`（即使状态不是 ACTIVE），再 `onDestroy()`

3. **统一异常消息语言**（问题 15）
   - 全部改为中文或全部改为英文，不要混用

4. **为 `PluginEventBus.close()` 添加状态防护**（问题 20）
   - 增加 `closed` 标记，关闭后 `publish()` 直接返回

### P1 — 生产环境需要

5. **添加核心指标导出**
   - 插件加载耗时、当前状态分布
   - 扩展注册数量（按扩展点类型分组）
   - 事件分发计数（按事件类型）
   - 使用 `System.Logger` 定期输出或通过 JMX Bean 暴露

6. **添加健康检查方法**
   - 在 `PluginManager` 上增加 `healthCheck()` 方法，返回所有插件的健康状态
   - 或提供 `isHealthy()` 快速检查

7. **修复动态代理白名单维护风险**（问题 10）
   - 用静态内部类替代 `createManagerView()` 中的反射代理

8. **添加 `PluginDescriptorLoader` strict mode**（问题 17）
   - 检查 `plugin.json` 中的未知字段并记录 WARNING

9. **修复 `reloadPlugin` 中的事件重复发布**（问题 13）
   - 检查当前状态，避免重复发布 `PluginFailedEvent`

### P2 — 质量提升

10. **为 sunsen-api 添加完整单元测试**（问题 22）
    - `AbstractPlugin` 的资源管理 LIFO 顺序
    - `ManagedScheduler` 的 shutdown 幂等性
    - `PluginProperties` 的各类型转换
    - `PluginCache` 的并发安全
    - `PluginFileSystem` 的路径越界检测

11. **添加并发压力测试**（问题 23）
    - `getExtensions()` + `reloadPlugin()` 并发
    - 多插件并行加载
    - 高频事件发布/订阅

12. **引入生命周期拦截器**（问题 11）
    - `PluginLifecycleInterceptor` 接口
    - 提供超时拦截器、日志拦截器的默认实现

13. **移除不必要的 `synchronized`**（问题 14）
    - `DefaultPluginContext.getProperty()` 的方法级同步

14. **提供 `plugin-schema.json`**（问题 25）
    - IDE 自动补全和校验支持

### P3 — 长期演进

15. **考虑插件签名验证**
    - 使用 JAR 签名机制验证插件来源

16. **考虑 `PluginContext` 接口拆分**（问题 1）
    - 按职责拆分为多个小接口

17. **考虑插件资源配额**
    - 如果应用场景可接受，不做硬限制，依赖文档约定

18. **添加 JMH 基准测试**
    - `getExtensions()` 吞吐量
    - 事件发布延迟
    - 类加载性能

---

## 七、总结

Sunsen 在其设计目标（轻量级 Java 插件框架）上做得很好。核心的 ClassLoader 隔离、扩展点模型、生命周期管理三大支柱的设计和实现都达到了较高的质量水平。代码整体是"一个人写的代码库"的典型特征——一致的风格、完整的注释、清晰的意图，没有多人协作常见的风格不一致问题。

**可以直接使用的场景**：开发工具、IDE 插件系统、企业内部中间件——这些场景对可观测性和安全纵深的要求相对较低。

**需要 P0/P1 优化后才能使用的场景**：生产级 SaaS 平台、需要热重载的长时间运行服务、有严格 SLA 的系统。

最关键的三个改进方向是：**超时机制**（防止插件挂起拖垮系统）、**可观测性**（让运维有能力排查问题）、**优雅降级**（失败时系统仍能部分运行）。

---

*本报告基于对 sunsen-core、sunsen-api、sunsen-server 全部源码及测试的审查。*
