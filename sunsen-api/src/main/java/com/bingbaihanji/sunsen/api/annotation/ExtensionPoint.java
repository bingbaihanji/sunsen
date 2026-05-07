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
     * 扩展点描述,供工具链、管理界面或文档生成使用
     */
    String description() default "";

    /**
     * 是否允许多个扩展实现同时存在
     * false 时框架在所有插件加载完成后校验该扩展点实现数量恰好为 1,
     * 否则拒绝启动并报告冲突
     */
    boolean allowMultiple() default true;
}
