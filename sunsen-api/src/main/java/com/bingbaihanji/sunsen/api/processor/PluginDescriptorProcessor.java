package com.bingbaihanji.sunsen.api.processor;

import com.bingbaihanji.sunsen.api.SunsenVersion;
import com.bingbaihanji.sunsen.api.annotation.Plugin;

import javax.annotation.processing.*;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.Element;
import javax.lang.model.element.TypeElement;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;
import java.io.IOException;
import java.io.Writer;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.regex.Pattern;

/**
 * 编译时扫描 {@link Plugin} 注解并生成 {@code META-INF/plugin.json}.
 * <p>
 * 若 {@code META-INF/plugin.json} 已存在(手动编写),处理器自动跳过,避免覆盖.
 */
@SupportedAnnotationTypes("com.bingbaihanji.sunsen.api.annotation.Plugin")
@SupportedSourceVersion(SourceVersion.RELEASE_21)
public class PluginDescriptorProcessor extends AbstractProcessor {

    private static final String OUTPUT_PATH = "META-INF/plugin.json";
    private static final Pattern PACKAGE_PATTERN = Pattern.compile(
            "[a-zA-Z_][a-zA-Z0-9_]*(\\.[a-zA-Z_][a-zA-Z0-9_]*)*");

    private Messager messager;
    private Filer filer;
    private final AtomicBoolean generated = new AtomicBoolean(false);

    @Override
    public synchronized void init(ProcessingEnvironment processingEnv) {
        super.init(processingEnv);
        this.messager = processingEnv.getMessager();
        this.filer = processingEnv.getFiler();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        if (annotations.isEmpty()) {
            return false;
        }

        for (Element element : roundEnv.getElementsAnnotatedWith(Plugin.class)) {
            if (!(element instanceof TypeElement typeElement)) {
                error(element, "@Plugin 只能标注在类上");
                continue;
            }

            Plugin plugin = typeElement.getAnnotation(Plugin.class);
            if (plugin == null) {
                continue;
            }

            // 校验必填字段
            String id = requireNonBlank(plugin.id(), "id", typeElement);
            String name = requireNonBlank(plugin.name(), "name", typeElement);
            String version = requireNonBlank(plugin.version(), "version", typeElement);
            String[] packagePrefixes = plugin.packagePrefixes();

            if (id == null || name == null || version == null) {
                continue;
            }
            if (packagePrefixes.length == 0) {
                error(typeElement, "@Plugin.packagePrefixes 至少提供一个包前缀");
                continue;
            }
            for (String prefix : packagePrefixes) {
                if (prefix == null || prefix.isBlank()) {
                    error(typeElement, "@Plugin.packagePrefixes 包含空值");
                    break;
                }
                String normalized = prefix.endsWith(".") ? prefix.substring(0, prefix.length() - 1) : prefix;
                if (!PACKAGE_PATTERN.matcher(normalized).matches()) {
                    error(typeElement, "@Plugin.packagePrefixes 包含非法包前缀: '" + prefix + "'");
                    break;
                }
            }

            // 推断 mainClass
            String mainClass = plugin.mainClass();
            if (mainClass == null || mainClass.isBlank()) {
                mainClass = typeElement.getQualifiedName().toString();
            }

            // 推断 apiVersion
            String apiVersion = plugin.apiVersion();
            if (apiVersion == null || apiVersion.isBlank()) {
                apiVersion = SunsenVersion.API_VERSION;
            }

            // 生成 JSON
            StringBuilder sb = new StringBuilder();
            sb.append("{\n");
            sb.append("  \"id\": \"").append(escapeJson(id)).append("\",\n");
            sb.append("  \"name\": \"").append(escapeJson(name)).append("\",\n");
            if (!plugin.description().isBlank()) {
                sb.append("  \"description\": \"").append(escapeJson(plugin.description())).append("\",\n");
            }
            sb.append("  \"version\": \"").append(escapeJson(version)).append("\",\n");
            sb.append("  \"apiVersion\": \"").append(escapeJson(apiVersion)).append("\",\n");
            sb.append("  \"mainClass\": \"").append(escapeJson(mainClass)).append("\",\n");
            sb.append("  \"packagePrefixes\": [\n");
            for (int i = 0; i < packagePrefixes.length; i++) {
                String normalized = packagePrefixes[i].endsWith(".")
                        ? packagePrefixes[i].substring(0, packagePrefixes[i].length() - 1)
                        : packagePrefixes[i];
                sb.append("    \"").append(escapeJson(normalized)).append("\"");
                if (i < packagePrefixes.length - 1) {
                    sb.append(",");
                }
                sb.append("\n");
            }
            sb.append("  ]");

            // dependencies
            Plugin.Dependency[] dependencies = plugin.dependencies();
            if (dependencies.length > 0) {
                sb.append(",\n");
                sb.append("  \"dependencies\": [\n");
                for (int i = 0; i < dependencies.length; i++) {
                    Plugin.Dependency dep = dependencies[i];
                    sb.append("    {\n");
                    sb.append("      \"id\": \"").append(escapeJson(dep.id())).append("\",\n");
                    sb.append("      \"version\": \"").append(escapeJson(dep.version())).append("\"");
                    if (dep.optional()) {
                        sb.append(",\n      \"optional\": true");
                    }
                    sb.append("\n    }");
                    if (i < dependencies.length - 1) {
                        sb.append(",");
                    }
                    sb.append("\n");
                }
                sb.append("  ]");
            }

            // permissions
            String[] permissions = plugin.permissions();
            if (permissions.length > 0) {
                sb.append(",\n");
                sb.append("  \"permissions\": [\n");
                for (int i = 0; i < permissions.length; i++) {
                    sb.append("    \"").append(escapeJson(permissions[i])).append("\"");
                    if (i < permissions.length - 1) {
                        sb.append(",");
                    }
                    sb.append("\n");
                }
                sb.append("  ]");
            }

            // vendor
            Plugin.Vendor[] vendors = plugin.vendor();
            if (vendors.length > 0) {
                sb.append(",\n");
                sb.append("  \"vendor\": {\n");
                int fieldCount = 0;
                for (Plugin.Vendor vendor : vendors) {
                    if (!vendor.name().isBlank()) {
                        if (fieldCount > 0) sb.append(",\n");
                        sb.append("    \"name\": \"").append(escapeJson(vendor.name())).append("\"");
                        fieldCount++;
                    }
                    if (!vendor.url().isBlank()) {
                        if (fieldCount > 0) sb.append(",\n");
                        sb.append("    \"url\": \"").append(escapeJson(vendor.url())).append("\"");
                        fieldCount++;
                    }
                }
                sb.append("\n  }");
            }

            sb.append("\n}\n");

            try {
                if (!generated.compareAndSet(false, true)) {
                    note(typeElement, "META-INF/plugin.json 已由同轮处理生成,跳过重复写入");
                    continue;
                }

                FileObject file = filer.createResource(
                        StandardLocation.CLASS_OUTPUT, "", OUTPUT_PATH, element);
                try (Writer writer = file.openWriter()) {
                    writer.write(sb.toString());
                }
                note(typeElement, "已生成 " + OUTPUT_PATH);
            } catch (FilerException e) {
                // 文件已由 Processor 或其他方式创建(如手动放置的 plugin.json)
                note(typeElement, "META-INF/plugin.json 已存在,跳过自动生成");
            } catch (IOException e) {
                error(typeElement, "无法写入 " + OUTPUT_PATH + ": " + e.getMessage());
            }
        }

        return true;
    }

    private String requireNonBlank(String value, String fieldName, TypeElement element) {
        if (value == null || value.isBlank()) {
            error(element, "@Plugin." + fieldName + " 不能为空");
            return null;
        }
        return value;
    }

    private void error(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.ERROR, message, element);
    }

    private void note(Element element, String message) {
        messager.printMessage(Diagnostic.Kind.NOTE, message, element);
    }

    private String escapeJson(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> sb.append(c);
            }
        }
        return sb.toString();
    }
}
