package com.bingbaihanji.sunsen.core.test.world;

import com.bingbaihanji.sunsen.api.Plugin;
import com.bingbaihanji.sunsen.api.PluginContext;
import com.bingbaihanji.sunsen.api.annotation.Extension;
import com.bingbaihanji.sunsen.core.test.ext.GreetingExtension;

/**
 * 测试插件:World
 */
public class WorldPlugin implements Plugin {
    @Override
    public void onInit(PluginContext context) {
    }
}

/**
 * 测试扩展:World 问候实现
 */
@Extension(order = 20)
class WorldGreeting implements GreetingExtension {
    @Override
    public String greet() {
        return "World";
    }
}
