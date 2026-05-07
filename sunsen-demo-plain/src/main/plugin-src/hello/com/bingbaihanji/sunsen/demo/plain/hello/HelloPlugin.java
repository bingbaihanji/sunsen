package com.bingbaihanji.sunsen.demo.plain.hello;

import com.bingbaihanji.sunsen.api.Plugin;
import com.bingbaihanji.sunsen.api.PluginContext;
import com.bingbaihanji.sunsen.api.annotation.Extension;
import com.bingbaihanji.sunsen.demo.plain.Greeter;

/**
 * Hello 演示插件,提供英语问候扩展实现
 */
public class HelloPlugin implements Plugin {
    @Override
    public void onInit(PluginContext context) {
        System.out.println("[HelloPlugin] onInit");
    }

    @Override
    public void onStart() {
        System.out.println("[HelloPlugin] onStart");
    }

    @Override
    public void onStop() {
        System.out.println("[HelloPlugin] onStop");
    }
}

@Extension(order = 10, description = "英语问候")
class EnglishGreeter implements Greeter {
    @Override
    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}
