package com.bingbaihanji.sunsen.api.annotation;

import java.lang.annotation.*;

/**
 * 标注在插件入口类上,声明插件元数据.
 * <p>
 * 编译时由 {@code PluginDescriptorProcessor} 自动生成 {@code META-INF/plugin.json},
 * 插件开发者无需手动维护 JSON 文件.
 * <p>
 * 典型用法:
 * <pre>{@code
 * @Plugin(
 *     id = "com.example.hello",
 *     name = "Hello Plugin",
 *     version = "1.0.0",
 *     packagePrefixes = "com.example.hello"
 * )
 * public class HelloPlugin extends AbstractPlugin {
 *     // ...
 * }
 * }</pre>
 *
 * @see com.bingbaihanji.sunsen.api.support.AbstractPlugin
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Plugin {

    /**
     * 插件全局唯一标识,建议反向域名格式
     */
    String id();

    /**
     * 插件展示名称
     */
    String name();

    /**
     * 插件描述信息
     */
    String description() default "";

    /**
     * 插件版本号,建议 SemVer 格式
     */
    String version();

    /**
     * 框架 API 兼容版本.
     * <p>
     * 默认空字符串表示使用框架当前 {@code SunsenVersion.API_VERSION}.
     * 仅当插件编译时依赖的框架版本与运行时不一致时才需要显式指定.
     */
    String apiVersion() default "";

    /**
     * 插件主类全限定名.
     * <p>
     * 默认空字符串表示自动推断为标注 {@code @Plugin} 的类本身.
     * 绝大多数场景无需手动填写.
     */
    String mainClass() default "";

    /**
     * 插件类所在的包前缀列表,类隔离的核心配置.
     * <p>
     * 至少提供一个前缀.多前缀场景(如插件包含多个子包)用数组形式:
     * {@code packagePrefixes = {"com.example.core", "com.example.util"}}
     */
    String[] packagePrefixes();

    /**
     * 插件依赖列表
     */
    Dependency[] dependencies() default {};

    /**
     * 运行时权限声明,由宿主在 {@code PluginContext} 中拦截
     */
    String[] permissions() default {};

    /**
     * 插件厂商信息
     */
    Vendor[] vendor() default {};

    /**
     * 插件依赖描述
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Dependency {
        /**
         * 依赖插件 ID
         */
        String id();

        /**
         * 依赖版本约束表达式,如 {@code ">=1.0.0"}、{@code "^1.2.0"}
         */
        String version();

        /**
         * 是否为可选依赖
         */
        boolean optional() default false;
    }

    /**
     * 插件厂商信息描述
     */
    @Documented
    @Retention(RetentionPolicy.RUNTIME)
    @Target({})
    @interface Vendor {
        /**
         * 厂商名称
         */
        String name() default "";

        /**
         * 厂商 URL
         */
        String url() default "";
    }
}
