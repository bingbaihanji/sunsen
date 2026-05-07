package com.bingbaihanji.sunsen.demo.plain;

import com.bingbaihanji.sunsen.api.annotation.ExtensionPoint;

/**
 * 问候语扩展点
 */
@ExtensionPoint(id = "greeter", description = "问候语扩展点")
public interface Greeter {
    /**
     * 生成问候语
     *
     * @param name 被问候的对象名称
     * @return 问候语文本
     */
    String greet(String name);
}
