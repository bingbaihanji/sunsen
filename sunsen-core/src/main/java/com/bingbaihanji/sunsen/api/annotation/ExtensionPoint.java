package com.bingbaihanji.sunsen.api.annotation;

import java.lang.annotation.*;

/**
 * 标注在接口上,声明该接口为一个可扩展的契约点
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface ExtensionPoint {

    /**
     * 扩展点的稳定标识符,独立于接口全限定名,适合跨版本引用
     * 默认为空字符串,框架自动使用接口全限定名作为 key
     */
    String id() default "";

    /**
     * 扩展点描述,供工具链,管理界面或文档生成使用
     */
    String description() default "";

    /**
     * 是否允许多个扩展实现同时存在.
     * false 时框架会校验同一插件内该扩展点的实现数量不能超过 1 个,
     * 否则加载失败. 跨插件的多个实现是允许的,
     * 若业务需要全局唯一,宿主侧需自行在拿到 getExtensions() 列表后做 size 校验.
     */
    boolean allowMultiple() default true;
}
