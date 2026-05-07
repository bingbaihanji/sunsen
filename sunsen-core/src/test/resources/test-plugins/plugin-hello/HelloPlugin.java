package com.bingbaihanji.sunsen.core.test.hello;

import com.bingbaihanji.sunsen.api.Plugin;
import com.bingbaihanji.sunsen.api.PluginContext;
import com.bingbaihanji.sunsen.api.annotation.Extension;
import com.bingbaihanji.sunsen.core.test.ext.GreetingExtension;

/**
 * 测试插件:Hello
 */
public class HelloPlugin implements Plugin {
    @Override
    public void onInit(PluginContext context) {
    }
}

/**
 * 测试扩展:Hello 问候实现
 */
@Extension(order = 10)
class HelloGreeting implements GreetingExtension {
    @Override
    public String greet() {
        return "Hello";
    }
}
