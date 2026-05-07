package com.bingbaihanji.sunsen.api;

/**
 * 插件生命周期接口
 */
public interface Plugin {

    /**
     * 注入上下文,做轻量初始化(读取配置、准备数据结构)
     * 禁止在此启动后台线程或注册监听器
     */
    void onInit(PluginContext context);

    /**
     * 启动业务逻辑、后台线程、注册外部监听器
     * 此时扩展已全部就绪,可安全调用 {@code getExtensions()}
     */
    default void onStart() {
    }

    /**
     * 停止后台线程、取消外部监听器、释放网络连接等可重新获取的资源
     */
    default void onStop() {
    }

    /**
     * ClassLoader 卸载前的最终清理,释放所有强引用,确保 GC 可回收
     */
    default void onDestroy() {
    }
}
