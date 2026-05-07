package com.bingbaihanji.sunsen.api;

import java.util.List;

/**
 * 检测到插件循环依赖时抛出
 */
public class CircularDependencyException extends SunsenException {

    // 循环依赖链上的插件 ID 列表
    private final List<String> cycle;

    /**
     * @param cycle 循环依赖链,如 ["plugin-a", "plugin-b", "plugin-a"]
     */
    public CircularDependencyException(List<String> cycle) {
        super("检测到循环依赖: " + String.join(" -> ", cycle));
        this.cycle = List.copyOf(cycle);
    }

    public List<String> getCycle() {
        return cycle;
    }
}
