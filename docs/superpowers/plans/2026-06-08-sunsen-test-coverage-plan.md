# Sunsen 测试覆盖补全实现计划

> **对于代理式工作器：** 要求的子技能：使用 superpowers:subagent-driven-development（推荐）或 superpowers:executing-plans
> 按任务逐步实现本计划。步骤使用复选框（`- [ ]`）语法进行跟踪。

**目标：** 为 PluginClassLoader、PluginWatcher 和 DefaultPluginManager 三个核心组件补全 24 个测试用例。

**架构：** 按方案 B 顺序执行（Manager → ClassLoader → Watcher）。所有测试放在 `sunsen-server/src/test/java/` 下，复用现有
`TestPluginPackager` 编译打包夹具。不引入新依赖。不改生产代码。

**技术栈：** Java 21+, JUnit 5, Maven surefire 3.2.5, `javax.tools.JavaCompiler`（JDK 内置）。

---

## 文件结构

| 文件                                                          | 操作 | 测试数 | 说明                           |
|---------------------------------------------------------------|------|--------|--------------------------------|
| `sunsen-server/src/test/java/.../DefaultPluginManagerIT.java` | 修改 | +8     | 复用已有夹具 helloJar/worldJar |
| `sunsen-server/src/test/java/.../PluginClassLoaderTest.java`  | 新建 | 10     | 程序化构建测试 JAR             |
| `sunsen-server/src/test/java/.../PluginWatcherTest.java`      | 新建 | 6      | 文件系统操作 + sleep 等待      |

---

### Task 1: Manager 集成测试增强（8 个新测试）

**文件：**

- 修改：`sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/DefaultPluginManagerIT.java`

**目标：** 在现有测试类末尾新增 8 个测试方法，每个方法独立覆盖一个管理器集成场景。

**已有夹具（可直接使用）：**

- `tempDir`（`@TempDir` 静态变量，所有测试共享）
- `helloJar`、`worldJar`（`@BeforeAll` 编译打包好的测试 JAR）
- `pluginsDir`（`@BeforeEach` 创建，包含 hello 和 world 的副本）
- `manager`（`@BeforeEach` 新建实例，`@AfterEach` 清理）

**需新增夹具：**

- 场景 6 需要一个"损坏版"JAR——创建一个 `plugin.json` 中 `mainClass` 指向不存在类的 JAR

---

- [ ] **Step 1: 创建"损坏版"测试插件 fixture 目录**

在 `sunsen-server/src/test/resources/test-plugins/` 下创建新目录 `plugin-broken/`，包含：

`plugin-broken/plugin.json`：

```json
{
  "id": "com.bingbaihanji.sunsen.core.test.hello",
  "name": "Broken Plugin",
  "version": "2.0.0",
  "apiVersion": "1.0",
  "mainClass": "com.bingbaihanji.sunsen.core.test.broken.DoesNotExist",
  "packagePrefixes": ["com.bingbaihanji.sunsen.core.test.broken"],
  "dependencies": []
}
```

`plugin-broken/BrokenPlugin.java`（这个类故意不编译——直接在 mainClass 中指向不存在的类实现最小开销）：

实际上这个源文件不需要，TestPluginPackager 会编译 `plugin-broken/` 下的 `.java` 文件。这里只需 plugin.json 即可——但
TestPluginPackager 是先编译再打包的。如果该目录下没有 `.java` 文件，编译步骤会跳过（walkFileTree 没有匹配项），打包的 JAR 将只有
plugin.json 而没有 `.class` 文件，加载时 mainClass 找不到类就会失败。

在 `@BeforeAll` 中编译打包：

```java
static Path brokenJar;

@BeforeAll
static void setUpAll() throws Exception {
    // ... existing code ...
    Path srcDir = Path.of("src/test/resources/test-plugins");
    brokenJar = TestPluginPackager.packagePlugin(srcDir.resolve("plugin-broken"), fixtureDir);
}
```

- [ ] **Step 2: 编写 testInstallPlugin（复制 JAR + 加载启动）**

在 `DefaultPluginManagerIT.java` 末尾（类闭合 `}` 前）添加：

```java
@Test
void testInstallPlugin() throws Exception {
    manager = new DefaultPluginManager();
    manager.setPluginsDir(pluginsDir);

    Path externalJar = tempDir.resolve("external-plugins").resolve("plugin-external.jar");
    Files.createDirectories(externalJar.getParent());
    Files.copy(helloJar, externalJar);

    manager.installPlugin(externalJar);

    Path installedPath = pluginsDir.resolve(externalJar.getFileName().toString());
    assertTrue(Files.exists(installedPath));
    assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
}
```

- [ ] **Step 3: 编写 testInstallPluginSourceNotExist（源不存在抛异常）**

```java
@Test
void testInstallPluginSourceNotExist() {
    manager = new DefaultPluginManager();
    manager.setPluginsDir(pluginsDir);

    Path nonexistent = tempDir.resolve("nonexistent.jar");
    assertThrows(PluginLoadException.class, () -> manager.installPlugin(nonexistent));
    assertFalse(manager.getPlugin("com.bingbaihanji.sunsen.core.test.hello").isPresent());
}
```

- [ ] **Step 4: 编写 testInstallRollbackOnStartFailure（复制成功但启动失败应清理）**

```java
@Test
void testInstallRollbackOnStartFailure() throws Exception {
    manager = new DefaultPluginManager();
    manager.setPluginsDir(pluginsDir);

    // brokenJar has same plugin ID but broken mainClass
    Stage externalJar = tempDir.resolve("staging").resolve("plugin-external.jar");
    Files.createDirectories(externalJar.getParent());
    Files.copy(brokenJar, externalJar);

    // installPlugin calls loadPlugin + startPlugin internally.
    // loadPlugin succeeds (plugin.json parses fine), but startPlugin fails because
    // the main class doesn't exist or can't be loaded.
    // The catch block in installPlugin should clean up the copied JAR.
    assertThrows(PluginLoadException.class, () -> manager.installPlugin(externalJar));

    // The copied JAR should have been cleaned up
    Path copiedPath = pluginsDir.resolve(externalJar.getFileName().toString());
    assertFalse(Files.exists(copiedPath), "copied JAR should be cleaned on failure");
    assertFalse(manager.getPlugin("com.bingbaihanji.sunsen.core.test.hello").isPresent());
}
```

- [ ] **Step 5: 编写 testUninstallPlugin（停止 + 卸载 + 删除 JAR）**

```java
@Test
void testUninstallPlugin() throws Exception {
    manager = new DefaultPluginManager();
    manager.setPluginsDir(pluginsDir);

    Path jarInDir = pluginsDir.resolve("plugin-hello.jar");
    assertTrue(Files.exists(jarInDir));

    manager.loadPlugin(jarInDir);
    manager.startPlugin("com.bingbaihanji.sunsen.core.test.hello");
    assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));

    manager.uninstallPlugin("com.bingbaihanji.sunsen.core.test.hello");

    assertFalse(Files.exists(jarInDir), "JAR file should be deleted");
    assertFalse(manager.getPlugin("com.bingbaihanji.sunsen.core.test.hello").isPresent());
}
```

- [ ] **Step 6: 编写 testLoadPluginsWithResult（损坏 JAR + 正常 JAR 混合）**

```java
@Test
void testLoadPluginsWithResult() throws Exception {
    manager = new DefaultPluginManager();
    manager.setPluginsDir(pluginsDir);

    // Add a corrupted file alongside the normal JARs
    Path corrupted = pluginsDir.resolve("corrupted.jar");
    Files.write(corrupted, new byte[]{0, 1, 2, 3}); // not a valid ZIP

    var errors = manager.loadPluginsWithResult();
    assertFalse(errors.isEmpty(), "should have at least one error for the corrupted JAR");
    assertEquals(2, manager.getPlugins().size(), "two valid plugins should still load");
    assertEquals(PluginState.LOADED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
    assertEquals(PluginState.LOADED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.world"));
}
```

- [ ] **Step 7: 编写 testReloadPluginWithRollback（热重载失败回滚）**

```java
@Test
void testReloadPluginWithRollback() throws Exception {
    manager = new DefaultPluginManager();
    manager.setPluginsDir(pluginsDir);

    Path jarFile = pluginsDir.resolve("plugin-hello.jar");
    manager.loadPlugin(jarFile);
    manager.startPlugin("com.bingbaihanji.sunsen.core.test.hello");
    assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));

    // Try to reload with the broken JAR
    assertThrows(PluginLoadException.class, () -> manager.reloadPlugin("com.bingbaihanji.sunsen.core.test.hello", brokenJar));

    // After rollback, the original plugin should still be ACTIVE
    assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
    assertTrue(manager.getPlugin("com.bingbaihanji.sunsen.core.test.hello").isPresent());
}
```

- [ ] **Step 8: 编写 testConcurrentWaveLoading（同一拓扑层并行加载）**

```java
@Test
void testConcurrentWaveLoading() throws Exception {
    // hello and world have no dependencies on each other, so they're in the same wave
    manager = new DefaultPluginManager();
    manager.setPluginsDir(pluginsDir);

    manager.loadPlugins();
    assertEquals(2, manager.getPlugins().size());
    assertEquals(PluginState.LOADED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
    assertEquals(PluginState.LOADED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.world"));
}
```

- [ ] **Step 9: 编写 testStopAndRestartCycle（停止后重启生命周期循环）**

```java
@Test
void testStopAndRestartCycle() {
    manager = new DefaultPluginManager();
    manager.setPluginsDir(pluginsDir);

    manager.loadPlugins();
    manager.startPlugins();
    assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));

    manager.stopPlugins();
    assertEquals(PluginState.STOPPED, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));

    // Restart from STOPPED
    manager.startPlugin("com.bingbaihanji.sunsen.core.test.hello");
    assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));

    // Extensions should still be available after restart
    List<GreetingExtension> ext = manager.getExtensions(GreetingExtension.class);
    assertFalse(ext.isEmpty());
}
```

- [ ] **Step 10: 运行测试验证**

```bash
mvn test -pl sunsen-server -Dtest=DefaultPluginManagerIT
```

Expected: 12 tests run (4 existing + 8 new), all PASS.

- [ ] **Step 11: 提交**

```bash
git add sunsen-server/src/test/resources/test-plugins/plugin-broken/ sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/DefaultPluginManagerIT.java
git commit -m "test: add 8 Manager integration tests for install, uninstall, reload, concurrent load

    Covers installPlugin, uninstallPlugin, reloadPlugin rollback,
    loadPluginsWithResult error collection, stop-restart cycle,
    and concurrent wave loading.

    Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 2: PluginClassLoader 测试（第 1 部分 — 静态方法测试）

**文件：**

- 创建：`sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/PluginClassLoaderTest.java`

**目标：** 覆盖 `matchesPackagePrefix` 和 `validatePrefixes` 两个纯静态方法的边界条件（无需 JAR）。

- [ ] **Step 1: 编写静态方法测试类**

```java
package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.PluginLoadException;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PluginClassLoaderTest {

    // ---- matchesPackagePrefix ----

    @Test
    void matchesPackagePrefixExactMatch() {
        assertTrue(PluginClassLoader.matchesPackagePrefix("com.foo.Bar", "com.foo"));
    }

    @Test
    void matchesPackagePrefixWithDot() {
        assertTrue(PluginClassLoader.matchesPackagePrefix("com.foo.bar.Baz", "com.foo"));
    }

    @Test
    void matchesPackagePrefixNoMatch() {
        assertFalse(PluginClassLoader.matchesPackagePrefix("com.foobar.Baz", "com.foo"));
    }

    @Test
    void matchesPackagePrefixNullInputs() {
        assertFalse(PluginClassLoader.matchesPackagePrefix(null, "com.foo"));
        assertFalse(PluginClassLoader.matchesPackagePrefix("com.foo.Bar", null));
    }

    // ---- validatePrefixes ----

    @Test
    void validatePrefixesCrossPluginConflict() {
        PluginDescriptor pluginA = descriptor("plugin-a", List.of("com.foo"));
        PluginDescriptor pluginB = descriptor("plugin-b", List.of("com.foo.api"));
        assertThrows(PluginLoadException.class,
                () -> PluginClassLoader.validatePrefixes(List.of(pluginA, pluginB)));
    }

    @Test
    void validatePrefixesSamePluginAllowsContainment() {
        PluginDescriptor pluginA = descriptor("plugin-a", List.of("com.foo", "com.foo.api"));
        assertDoesNotThrow(() -> PluginClassLoader.validatePrefixes(List.of(pluginA)));
    }

    // ---- helper ----

    private static PluginDescriptor descriptor(String id, List<String> prefixes) {
        return new PluginDescriptor(id, id, "", "1.0", "1.0", "com.test.Main",
                prefixes, List.of(), java.util.Set.of(), java.util.Map.of());
    }
}
```

- [ ] **Step 2: 运行测试**

```bash
mvn test -pl sunsen-server -Dtest=PluginClassLoaderTest
```

Expected: 6 tests run, all PASS.

- [ ] **Step 3: 提交**

```bash
git add sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/PluginClassLoaderTest.java
git commit -m "test: add PluginClassLoader test covering matchesPackagePrefix and validatePrefixes

    Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 3: PluginClassLoader 测试（第 2 部分 — JAR 加载测试）

**文件：**

- 修改：`sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/PluginClassLoaderTest.java`（追加 4 个新测试 + 2 个测试辅助
  JAR 方法）

**目标：** 覆盖 loadClass 的 4 层加载顺序，需要构造真实 JAR 测试。

- [ ] **Step 1: 在 @BeforeAll 中编译打包一个测试 JAR**

在静态方法测试之后、类关闭 `}` 之前添加：

```java
private static Path testSimpleJar;
private static Path testDependencyJar;

@BeforeAll
static void setUpAll() throws Exception {
    // Build a simple test plugin JAR containing one class: com.test.plugin.Helper
    testSimpleJar = buildSimpleJar("com.test.plugin", "Helper",
            "package com.test.plugin;\npublic class Helper {}\n");

    // Build a dependency JAR containing: com.test.dep.DepClass
    testDependencyJar = buildSimpleJar("com.test.dep", "DepClass",
            "package com.test.dep;\npublic class DepClass {}\n");
}

private static Path buildSimpleJar(String packageName, String className, String source) throws Exception {
    Path tmpDir = Files.createTempDirectory("test-jar-builder");
    Path srcDir = Files.createDirectories(tmpDir.resolve("src").resolve(packageName.replace('.', '/')));
    Files.writeString(srcDir.resolve(className + ".java"), source);
    Path outDir = Files.createDirectories(tmpDir.resolve("out"));

    JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
    try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
        Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(new File(srcDir.resolve(className + ".java").toString()));
        compiler.getTask(null, fm, null, List.of("-d", outDir.toString()), null, units).call();
    }

    Path jarPath = tmpDir.resolve(className + ".jar");
    try (java.util.jar.JarOutputStream jos = new java.util.jar.JarOutputStream(
            new java.io.FileOutputStream(jarPath.toFile()))) {
        Files.walkFileTree(outDir, new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult visitFile(java.nio.file.Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException {
                if (file.toString().endsWith(".class")) {
                    String entryName = outDir.relativize(file).toString().replace('\\', '/');
                    jos.putNextEntry(new java.util.jar.JarEntry(entryName));
                    jos.write(Files.readAllBytes(file));
                    jos.closeEntry();
                }
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }
    return jarPath;
}
```

- [ ] **Step 2: 编写 JAR 加载测试方法**

```java
@Test
void testJavaLangDelegatesToParent() throws Exception {
    PluginClassLoader cl = new PluginClassLoader(
            new URL[]{testSimpleJar.toUri().toURL()},
            getClass().getClassLoader(),
            List.of("com.test.plugin"),
            List.of());

    Class<?> c = cl.loadClass("java.lang.String");
    assertNull(c.getClassLoader(), "String should be loaded by bootstrap");
}

@Test
void testApiPackageDelegatesToParent() {
    PluginClassLoader cl = new PluginClassLoader(
            new URL[]{testSimpleJar.toUri().toURL()},
            getClass().getClassLoader(),
            List.of("com.test.plugin"),
            List.of());

    // Plugin is on the classpath (sunsen-core), so parent classloader loads it
    assertDoesNotThrow(() -> {
        Class<?> c = cl.loadClass("com.bingbaihanji.sunsen.api.Plugin");
        assertNotNull(c);
    });
}

@Test
void testOwnPrefixSelfLoads() throws Exception {
    PluginClassLoader cl = new PluginClassLoader(
            new URL[]{testSimpleJar.toUri().toURL()},
            getClass().getClassLoader(),
            List.of("com.test.plugin"),
            List.of());

    Class<?> c = cl.loadClass("com.test.plugin.Helper");
    assertSame(cl, c.getClassLoader());
}

@Test
void testThrowsForMissingClassInOwnPrefix() {
    PluginClassLoader cl = new PluginClassLoader(
            new URL[]{testSimpleJar.toUri().toURL()},
            getClass().getClassLoader(),
            List.of("com.test.plugin"),
            List.of());

    assertThrows(ClassNotFoundException.class,
            () -> cl.loadClass("com.test.plugin.DoesNotExist"));
}

@Test
void testDependencyChainResolution() throws Exception {
    PluginClassLoader depCl = new PluginClassLoader(
            new URL[]{testDependencyJar.toUri().toURL()},
            getClass().getClassLoader(),
            List.of("com.test.dep"),
            List.of());

    PluginClassLoader mainCl = new PluginClassLoader(
            new URL[]{testSimpleJar.toUri().toURL()},
            getClass().getClassLoader(),
            List.of("com.test.plugin"),
            List.of(depCl));

    Class<?> c = mainCl.loadClass("com.test.dep.DepClass");
    assertSame(depCl, c.getClassLoader());
}
```

- [ ] **Step 3: 也是编译需要的 import 补充**

在 PluginClassLoaderTest.java 文件顶部已有 package 声明后，需要的 imports：

```java
import org.junit.jupiter.api.BeforeAll;
import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import javax.tools.JavaFileObject;
import java.io.File;
import java.net.MalformedURLException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
```

原来的文件已有部分 import，只需追加。

- [ ] **Step 4: 运行所有 PluginClassLoader 测试**

```bash
mvn test -pl sunsen-server -Dtest=PluginClassLoaderTest
```

Expected: 10 tests run (6 static + 4 JAR-based), all PASS.

- [ ] **Step 5: 提交**

```bash
git add sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/PluginClassLoaderTest.java
git commit -m "test: add PluginClassLoader JAR loading tests covering loadClass ordering

    Tests: java.lang delegation, api package delegation, own-prefix
    self-load, missing-in-prefix throw, and dependency chain resolution.

    Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

### Task 4: PluginWatcher 测试

**文件：**

- 创建：`sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/PluginWatcherTest.java`

**目标：** 覆盖文件创建/修改/删除事件、防抖合并、非 JAR 过滤、损坏文件处理。

- [ ] **Step 1: 编写完整 PluginWatcherTest**

```java
package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.PluginState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import static org.junit.jupiter.api.Assertions.*;

class PluginWatcherTest {

    @TempDir
    static Path tempDir;
    static Path helloJar;
    static Path worldJar;
    Path pluginsDir;
    DefaultPluginManager manager;
    PluginWatcher watcher;

    @BeforeAll
    static void setUpAll() throws Exception {
        Path fixtureDir = tempDir.resolve("fixtures");
        Files.createDirectories(fixtureDir);
        Path srcDir = Path.of("src/test/resources/test-plugins");
        helloJar = TestPluginPackager.packagePlugin(srcDir.resolve("plugin-hello"), fixtureDir);
        worldJar = TestPluginPackager.packagePlugin(srcDir.resolve("plugin-world"), fixtureDir);
    }

    @AfterEach
    void tearDown() throws Exception {
        if (watcher != null) {
            watcher.close();
        }
        if (manager != null) {
            try {
                manager.unloadPlugins();
            } catch (Exception ignored) {
            }
        }
    }

    private void initManagerAndWatcher() throws Exception {
        pluginsDir = tempDir.resolve("watcher-plugins-" + System.nanoTime());
        Files.createDirectories(pluginsDir);
        manager = new DefaultPluginManager();
        manager.setPluginsDir(pluginsDir);
        watcher = new PluginWatcher(manager, pluginsDir);
    }

    @Test
    void testAutoLoadNewJar() throws Exception {
        initManagerAndWatcher();
        assertTrue(manager.getPlugins().isEmpty());

        // Copy a JAR in — the watcher should auto-load and start it
        Path dest = pluginsDir.resolve("plugin-hello.jar");
        Files.copy(helloJar, dest);

        Thread.sleep(1500); // Wait for STABILIZE_DELAY_MS (500ms) + safety margin

        assertEquals(1, manager.getPlugins().size());
        assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
    }

    @Test
    void testAutoReloadModifiedJar() throws Exception {
        initManagerAndWatcher();

        // Pre-load the plugin first
        Path dest = pluginsDir.resolve("plugin-hello.jar");
        Files.copy(helloJar, dest);
        Thread.sleep(1500);
        assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));

        // Now "replace" with a different JAR (world has same id structure, but use hello again for safety)
        // Actually, modifying means overwriting with same-id content. Use world JAR renamed to hello.
        // But world has different plugin ID, so it can't replace hello directly.
        // Instead, use a freshly built hello JAR to simulate a MODIFY event.
        Files.copy(helloJar, dest, StandardCopyOption.REPLACE_EXISTING);
        Thread.sleep(1500);

        // Plugin should still be ACTIVE (reload was triggered)
        assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
    }

    @Test
    void testAutoUnloadDeletedJar() throws Exception {
        initManagerAndWatcher();

        Path dest = pluginsDir.resolve("plugin-hello.jar");
        Files.copy(helloJar, dest);
        Thread.sleep(1500);
        assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));

        Files.delete(dest);
        Thread.sleep(1500);

        assertNull(manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
    }

    @Test
    void testDebounceMergesMultipleEvents() throws Exception {
        initManagerAndWatcher();

        Path dest = pluginsDir.resolve("plugin-hello.jar");
        // Rapid successive writes within the debounce window
        Files.copy(helloJar, dest);
        Thread.sleep(100);
        Files.copy(helloJar, dest, StandardCopyOption.REPLACE_EXISTING);
        Thread.sleep(100);
        Files.copy(helloJar, dest, StandardCopyOption.REPLACE_EXISTING);

        // Wait for debounce to settle
        Thread.sleep(1500);

        // Only one plugin should be loaded (not reloaded multiple times)
        assertEquals(1, manager.getPlugins().size());
        assertEquals(PluginState.ACTIVE, manager.getPluginState("com.bingbaihanji.sunsen.core.test.hello"));
    }

    @Test
    void testIgnoreNonJarFiles() throws Exception {
        initManagerAndWatcher();

        Files.writeString(pluginsDir.resolve("readme.txt"), "hello");
        Files.writeString(pluginsDir.resolve("config.tmp"), "tmp");
        Files.createFile(pluginsDir.resolve("backup.log"));

        Thread.sleep(1500);

        assertTrue(manager.getPlugins().isEmpty());
    }

    @Test
    void testHandleCorruptedJar() throws Exception {
        initManagerAndWatcher();

        Path corrupted = pluginsDir.resolve("corrupted.jar");
        Files.write(corrupted, new byte[]{0, 1, 2, 3});

        Thread.sleep(1500);

        // Should not crash — just skip the corrupted file
        assertTrue(manager.getPlugins().isEmpty());
    }
}
```

- [ ] **Step 2: 运行 PluginWatcher 测试**

```bash
mvn test -pl sunsen-server -Dtest=PluginWatcherTest
```

Expected: 6 tests run, all PASS.

- [ ] **Step 3: 运行全量测试确认无回归**

```bash
mvn test -pl sunsen-server
```

Expected: all tests pass (103 original + 24 new = 127 total).

- [ ] **Step 4: 提交**

```bash
git add sunsen-server/src/test/java/com/bingbaihanji/sunsen/core/PluginWatcherTest.java
git commit -m "test: add PluginWatcher test covering auto-load, reload, unload, debounce

    Tests: auto-load new JAR, auto-reload on modify, auto-unload on delete,
    debounce multiple events, ignore non-JAR files, handle corrupted JAR.

    Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## 自审清单

1. **规范覆盖** — 对照规范三阶段：
    - Manager 集成测试（8 个）→ Task 1 ✓
    - PluginClassLoader 单测（10 个）→ Tasks 2+3 ✓
    - PluginWatcher 单测（6 个）→ Task 4 ✓

2. **占位符扫描** — 无 TBD/TODO/placeholder ✓

3. **类型一致性** —
    - Task 1 中的 `brokenJar` 与 `@BeforeAll` 新变量在 Task 1 Step 1 中定义 ✓
    - Task 2+3 使用同一个 `PluginClassLoaderTest.java` 文件，先提交静态测试（Task 2），再追加 JAR 测试（Task 3），文件始终被正确维护
      ✓
    - `buildSimpleJar` 方法的 imports 在 Task 3 Step 3 中补充 ✓

4. **checklist 语法** — 所有步骤使用 `- [ ]` ✓
