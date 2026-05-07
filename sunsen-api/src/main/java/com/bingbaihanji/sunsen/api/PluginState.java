package com.bingbaihanji.sunsen.api;

/**
 * 插件生命周期状态枚举
 */
public enum PluginState {
    CREATED,   // 插件已创建 
    RESOLVED,  // 依赖已解析 ClassLoader已初始化
    LOADED,    // 主类已实例化 onInit已调用
    STARTING,  // 正在启动中
    ACTIVE,    // 已启动 onStart已完成
    STOPPING,  // 正在停止中
    STOPPED,   // 已停止 onStop已完成
    UNLOADED,  // 已卸载 ClassLoader已释放
    FAILED     // 生命周期中出现异常
}