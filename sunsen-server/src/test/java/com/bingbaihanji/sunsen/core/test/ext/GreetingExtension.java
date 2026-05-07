package com.bingbaihanji.sunsen.core.test.ext;

import com.bingbaihanji.sunsen.api.annotation.ExtensionPoint;

/**
 * 测试用问候语扩展点
 */
@ExtensionPoint(id = "greeting", description = "问候语扩展点")
public interface GreetingExtension {
    /**
     * 返回问候语
     */
    String greet();
}
