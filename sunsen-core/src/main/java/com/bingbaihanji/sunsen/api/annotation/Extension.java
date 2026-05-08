package com.bingbaihanji.sunsen.api.annotation;

import com.bingbaihanji.sunsen.api.PluginDescriptor;

import java.lang.annotation.*;

/**
 * 标注在实现类上,声明该类为某扩展点的一个实现
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
public @interface Extension {

    /**
     * 该扩展的唯一标识,用于按名称精确获取单个扩展实例
     * 默认为空字符串,框架自动使用实现类全限定名作为 id
     */
    String id() default "";

    /**
     * 排序权重值越小越靠前(优先级越高)默认为 0
     */
    int order() default 0;

    /**
     * 是否单例
     * true(默认):框架在注册时创建唯一实例
     * false:每次获取时创建新实例
     */
    boolean singleton() default true;

    /**
     * 扩展描述,供工具链或管理界面展示
     */
    String description() default "";
}
