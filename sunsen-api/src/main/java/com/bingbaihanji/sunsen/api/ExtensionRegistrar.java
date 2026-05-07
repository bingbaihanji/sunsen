package com.bingbaihanji.sunsen.api;

/**
 * 扩展实例生命周期钩子
 * 框架创建和销毁扩展实例时触发此接口,用于将扩展生命周期同步到宿主容器
 */
public interface ExtensionRegistrar {

    /**
     * 扩展实例创建并注册到 ExtensionRegistry 后调用
     */
    default void afterExtensionCreated(Object extensionInstance, Class<?> extensionPointType) {
    }

    /**
     * 扩展实例从 ExtensionRegistry 注销前调用
     * 务必在此释放所有对 extensionInstance 的强引用,以便 GC 回收 ClassLoader
     */
    default void afterExtensionDestroyed(Object extensionInstance, Class<?> extensionPointType) {
    }
}
