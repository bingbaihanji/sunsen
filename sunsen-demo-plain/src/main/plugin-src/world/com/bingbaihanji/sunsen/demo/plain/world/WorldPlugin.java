package com.bingbaihanji.sunsen.demo.plain.world;

import com.bingbaihanji.sunsen.api.Plugin;
import com.bingbaihanji.sunsen.api.PluginContext;
import com.bingbaihanji.sunsen.api.annotation.Extension;
import com.bingbaihanji.sunsen.demo.plain.Greeter;

/**
 * World 演示插件,提供中文问候扩展实现
 */
public class WorldPlugin implements Plugin {
    @Override
    public void onInit(PluginContext context) {
        System.out.println("[WorldPlugin] onInit");
    }

    @Override
    public void onStart() {
        System.out.println("[WorldPlugin] onStart");
    }

    @Override
    public void onStop() {
        System.out.println("[WorldPlugin] onStop");
    }
}

@Extension(order = 20, description = "中文问候")
class ChineseGreeter implements Greeter {
    @Override
    public String greet(String name) {
        return "小逼崽子" + name + "你好！";
    }
}
