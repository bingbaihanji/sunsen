package com.bingbaihanji.sunsen.core;

import javax.tools.JavaCompiler;
import javax.tools.StandardJavaFileManager;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

/**
 * 测试辅助类:将测试插件源码编译并打包成 JAR
 */
public class TestPluginPackager {

    public static Path packagePlugin(Path pluginSrcDir, Path outputDir) throws Exception {
        String pluginName = pluginSrcDir.getFileName().toString();
        Path classOutputDir = outputDir.resolve(pluginName + "-classes");
        Files.createDirectories(classOutputDir);

        // 收集 .java 源文件
        List<Path> javaFiles = new ArrayList<>();
        Files.walkFileTree(pluginSrcDir, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) {
                if (file.toString().endsWith(".java")) {
                    javaFiles.add(file);
                }
                return FileVisitResult.CONTINUE;
            }
        });

        // 编译
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("系统 Java 编译器不可用,请确保运行的是 JDK 而非 JRE");
        }
        try (StandardJavaFileManager fileManager = compiler.getStandardFileManager(null, null, null)) {
            Iterable<? extends javax.tools.JavaFileObject> compilationUnits =
                    fileManager.getJavaFileObjectsFromPaths(javaFiles);
            List<String> options = List.of(
                    "-d", classOutputDir.toString(),
                    "-cp", System.getProperty("java.class.path")
            );
            JavaCompiler.CompilationTask task = compiler.getTask(
                    null, fileManager, null, options, null, compilationUnits);
            boolean success = task.call();
            if (!success) {
                throw new IllegalStateException("编译测试插件失败: " + pluginName);
            }
        }

        // 打包 JAR
        Path jarPath = outputDir.resolve(pluginName + ".jar");
        try (JarOutputStream jos = new JarOutputStream(Files.newOutputStream(jarPath))) {
            // 写入 plugin.json
            Path pluginJson = pluginSrcDir.resolve("plugin.json");
            if (Files.exists(pluginJson)) {
                jos.putNextEntry(new JarEntry("META-INF/plugin.json"));
                jos.write(Files.readAllBytes(pluginJson));
                jos.closeEntry();
            }

            // 写入 .class 文件
            Files.walkFileTree(classOutputDir, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (file.toString().endsWith(".class")) {
                        String entryName = classOutputDir.relativize(file).toString().replace('\\', '/');
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
}
