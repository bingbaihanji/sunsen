package com.bingbaihanji.sunsen.core;

import com.bingbaihanji.sunsen.api.PluginDescriptor;
import com.bingbaihanji.sunsen.api.PluginLoadException;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.net.URL;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link PluginClassLoader} 单元测试
 * @author 冰白寒祭
 * @date 2025
 */
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

    // ---- JAR-based loadClass tests ----

    private static Path testSimpleJar;
    private static Path testDependencyJar;

    @BeforeAll
    static void setUpAll() throws Exception {
        testSimpleJar = buildSimpleJar("com.test.plugin", "Helper",
                "package com.test.plugin;\npublic class Helper {}\n");
        testDependencyJar = buildSimpleJar("com.test.dep", "DepClass",
                "package com.test.dep;\npublic class DepClass {}\n");
    }

    private static Path buildSimpleJar(String packageName, String className, String source) throws Exception {
        Path tmpDir = Files.createTempDirectory("test-jar-builder-" + className);
        Path srcDirPath = tmpDir.resolve("src").resolve(packageName.replace('.', '/'));
        Files.createDirectories(srcDirPath);
        Files.writeString(srcDirPath.resolve(className + ".java"), source);
        Path outDir = Files.createDirectories(tmpDir.resolve("out"));

        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends JavaFileObject> units = fm.getJavaFileObjects(
                    new File(srcDirPath.resolve(className + ".java").toString()));
            compiler.getTask(null, fm, null, List.of("-d", outDir.toString()), null, units).call();
        }

        Path jarPath = tmpDir.resolve(className + ".jar");
        try (JarOutputStream jos = new JarOutputStream(new FileOutputStream(jarPath.toFile()))) {
            Files.walkFileTree(outDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws java.io.IOException {
                    if (file.toString().endsWith(".class")) {
                        String entryName = outDir.relativize(file).toString().replace('\\', '/');
                        jos.putNextEntry(new JarEntry(entryName));
                        jos.write(Files.readAllBytes(file));
                        jos.closeEntry();
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        }
        return jarPath;
    }

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
    void testApiPackageDelegatesToParent() throws Exception {
        PluginClassLoader cl = new PluginClassLoader(
                new URL[]{testSimpleJar.toUri().toURL()},
                getClass().getClassLoader(),
                List.of("com.test.plugin"),
                List.of());

        Class<?> c = cl.loadClass("com.bingbaihanji.sunsen.api.Plugin");
        assertNotNull(c);
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
    void testThrowsForMissingClassInOwnPrefix() throws Exception {
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

    // ---- helper ----

    private static PluginDescriptor descriptor(String id, List<String> prefixes) {
        return new PluginDescriptor(id, id, "", "1.0", "1.0", "com.test.Main",
                prefixes, List.of(), java.util.Set.of(), java.util.Map.of());
    }
}
