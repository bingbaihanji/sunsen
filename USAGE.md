# Sunsen 插件框架 — 使用指南

> 本文档面向**宿主应用开发者**与**插件开发者**,帮助你在 5 分钟内上手 Sunsen

---

## 一、引入依赖

### Maven

Sunsen 采用多模块结构,宿主应用只需引入 `sunsen-core`:

```xml

<dependency>
    <groupId>com.bingbaihanji</groupId>
    <artifactId>sunsen-core</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

`mvn install` 后将自动拉取其唯一的编译依赖 `sunsen-api`

### 最低环境要求

- **JDK 21+**(框架本身零第三方依赖,仅 `sunsen-core` 依赖 `jackson-databind` 解析 `plugin.json`)

---

## 二、宿主应用接入(5 分钟速成)

### 2.1 定义扩展点

扩展点是**宿主与插件之间的契约接口**,需标注 `@ExtensionPoint`:

```java
package com.example.app.ext;

import com.bingbaihanji.sunsen.api.annotation.ExtensionPoint;

@ExtensionPoint(id = "greeting", description = "问候语扩展点")
public interface Greeter {
    String greet(String name);
}
```

**关键规则**:

- 扩展点接口必须位于**宿主 classpath**(或 sunsen-api 模块),确保所有插件通过父 ClassLoader 共享同一类型
- `id` 为空时,框架自动使用接口全限定名作为 key

### 2.2 创建插件管理器

```java
import com.bingbaihanji.sunsen.core.DefaultPluginManager;

import java.nio.file.Path;

public class MyApp {
    public static void main(String[] args) {
        DefaultPluginManager manager = new DefaultPluginManager();
        manager.setPluginsDir(Path.of("plugins"));

        // 可选:注册扩展生命周期钩子,将扩展同步到 Spring / JavaFX 等容器
        manager.setExtensionRegistrar(new MyExtensionRegistrar());

        // 批量加载 + 启动
        manager.loadPlugins();   // 扫描 → 解析 → 依赖校验 → 类加载 → 扩展注册
        manager.startPlugins();  // 按拓扑顺序调用 onStart()

        // 使用扩展
        manager.getExtensions(Greeter.class).forEach(g -> {
            System.out.println(g.greet("Sunsen"));
        });

        // 优雅停机
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            manager.stopPlugins();
            manager.unloadPlugins();
        }));
    }
}
```

### 2.3 监听插件生命周期事件

```java
manager.subscribe(PluginStartedEvent .class, event ->{
        System.out.

println("插件已启动: "+event.getSourcePluginId());
        });

        manager.

subscribe(PluginFailedEvent .class, event ->{
        System.err.

println("插件异常: "+event.getSourcePluginId() 
        +", 原因: "+event.

getCause().

getMessage());
        });
```

---

## 三、插件开发指南

### 3.1 插件项目结构

插件是一个**普通的 JAR 包**,结构如下:

```
my-plugin.jar
├── META-INF/
│   └── plugin.json          ← 插件身份、依赖、扩展声明
├── com/
│   └── example/
│       └── plugin/
│           ├── MyPlugin.class       ← 入口类,实现 Plugin 接口
│           └── MyExtension.class    ← 扩展实现类,标注 @Extension
```

### 3.2 编写 plugin.json

```json
{
  "id": "com.example.plugin.hello",
  "name": "Hello Plugin",
  "description": "提供英语问候能力",
  "version": "1.0.0",
  "apiVersion": "1.0",
  "mainClass": "com.example.plugin.hello.HelloPlugin",
  "packagePrefixes": [
    "com.example.plugin.hello"
  ],
  "dependencies": [
    {
      "id": "com.example.plugin.base",
      "version": ">=1.0.0 <2.0.0",
      "optional": false
    }
  ],
  "permissions": [
    "file:read",
    "file:write"
  ]
}
```

| 字段                | 必填 | 说明                              |
|-------------------|----|---------------------------------|
| `id`              | ✅  | 全局唯一标识,建议反向域名格式                 |
| `name`            | ✅  | 展示名称                            |
| `version`         | ✅  | SemVer 格式,如 `1.2.0`             |
| `apiVersion`      | ✅  | 编译时依赖的 Sunsen API 主版本号          |
| `mainClass`       | ✅  | 实现 `Plugin` 接口的入口类全限定名          |
| `packagePrefixes` | ✅  | 插件私有包前缀,类隔离的核心配置                |
| `dependencies`    | ❌  | 依赖插件列表,支持语义化版本约束                |
| `permissions`     | ❌  | 运行时权限声明(由宿主在 PluginContext 中拦截) |

### 3.3 实现 Plugin 接口

```java
package com.example.plugin.hello;

import com.bingbaihanji.sunsen.api.Plugin;
import com.bingbaihanji.sunsen.api.PluginContext;

public class HelloPlugin implements Plugin {

    private PluginContext context;

    @Override
    public void onInit(PluginContext context) {
        this.context = context;
        // 轻量初始化:读取配置、准备数据结构
        // 禁止在此处启动后台线程或注册监听器
    }

    @Override
    public void onStart() {
        // 启动业务逻辑、后台线程、注册外部监听器
        // 此时所有插件的扩展已全部就绪
    }

    @Override
    public void onStop() {
        // 停止后台线程、取消监听器
    }

    @Override
    public void onDestroy() {
        // 最终清理,释放所有强引用
        this.context = null;
    }
}
```

**生命周期原则**:

- `onInit()`:只读配置、做准备,**禁止注册监听器或启动线程**
- `onStart()`:业务启动,此时可安全调用 `context.getExtensions(...)`
- `onStop()`:释放可重新获取的资源
- `onDestroy()`:解除所有强引用,确保 ClassLoader 可被 GC

### 3.4 实现扩展

```java
package com.example.plugin.hello;

import com.bingbaihanji.sunsen.api.annotation.Extension;
import com.example.app.ext.Greeter;

@Extension(order = 10, description = "英语问候")
public class EnglishGreeter implements Greeter {
    @Override
    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}
```

**`@Extension` 属性说明**:

| 属性            | 默认值     | 说明                                      |
|---------------|---------|-----------------------------------------|
| `id`          | `类全限定名` | 扩展唯一标识                                  |
| `order`       | `0`     | 排序权重,值越小越靠前                             |
| `singleton`   | `true`  | 是否单例`false` 时每次 `getExtensions()` 创建新实例 |
| `description` | `""`    | 扩展描述                                    |

### 3.5 打包插件

插件最终产物是一个包含 `META-INF/plugin.json` 的 **标准 JAR 文件**

**Maven 示例**:

```xml

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-jar-plugin</artifactId>
            <configuration>
                <archive>
                    <manifestEntries>
                        <!-- 无特殊要求,plugin.json 已放在 src/main/resources/META-INF/ 下即可 -->
                    </manifestEntries>
                </archive>
            </configuration>
        </plugin>
    </plugins>
</build>
```

将打包后的 JAR 放入宿主指定的 `plugins/` 目录即可

---

## 四、高级特性

### 4.1 热重载(Hot Reload)

在不重启宿主的前提下替换插件:

```java
Path newJar = Path.of("plugins/plugin-hello-2.0.0.jar");
manager.

reloadPlugin("com.example.plugin.hello",newJar);
```

框架原子执行以下序列:

1. 获取 `ExtensionRegistry` 写锁
2. 旧插件 `onStop()` → `onDestroy()` → 状态 `UNLOADED`
3. 注销旧扩展,触发 `afterExtensionDestroyed`
4. 释放旧 ClassLoader
5. 用新 JAR 创建新 ClassLoader,解析 `plugin.json`
6. 扫描新扩展并注册,触发 `afterExtensionCreated`
7. 释放写锁
8. 新插件 `onStart()` → 状态 `ACTIVE`

### 4.2 插件间依赖

`plugin.json` 中声明依赖后,框架自动完成:

- **存在性校验**:非 optional 依赖必须存在
- **版本匹配**:支持 `>=1.0.0 <2.0.0`、`^1.2.0`、`~1.2.0`、精确版本
- **循环依赖检测**:DFS 检测环路,发现即拒绝加载
- **拓扑排序**:Kahn 算法确保依赖插件先加载、先启动

依赖插件的 ClassLoader 会自动加入被依赖插件的 ClassLoader 查找链,因此被依赖插件的类对依赖方可见

### 4.3 权限控制(PluginContext 网关拦截)

由于 Java 21 已移除 `SecurityManager`,Sunsen 采用**网关拦截模式**:

```java
public class SecurePluginContext extends DefaultPluginContext {

    @Override
    public Path getPluginWorkDir() {
        requirePermission("file:read");
        return super.getPluginWorkDir();
    }

    private void requirePermission(String permission) {
        if (!getDescriptor().getPermissions().contains(permission)) {
            throw new PluginPermissionException(
                    getDescriptor().getId() + " 未声明权限: " + permission
            );
        }
    }
}
```

自定义 `PluginContext` 后,需在 `DefaultPluginManager` 中替换(当前版本可通过继承 `DefaultPluginManager` 并重写创建
Context 的方法实现)

### 4.4 与 Spring Boot 集成思路

```java

@Configuration
public class SunsenConfig {

    @Bean(initMethod = "startPlugins", destroyMethod = "stopPlugins")
    public DefaultPluginManager pluginManager(ApplicationContext ctx) {
        DefaultPluginManager manager = new DefaultPluginManager();
        manager.setPluginsDir(Path.of("plugins"));

        // 将扩展实例同步注册为 Spring Bean
        manager.setExtensionRegistrar(new ExtensionRegistrar() {
            @Override
            public void afterExtensionCreated(Object instance, Class<?> epType) {
                ((ConfigurableApplicationContext) ctx)
                        .getBeanFactory()
                        .registerSingleton(instance.getClass().getName(), instance);
            }

            @Override
            public void afterExtensionDestroyed(Object instance, Class<?> epType) {
                // 从 Spring 单例注册表移除
            }
        });

        manager.loadPlugins();
        return manager;
    }
}
```

---

## 五、API 速查

### PluginManager(宿主侧)

| 方法                             | 说明                     |
|--------------------------------|------------------------|
| `loadPlugins()`                | 扫描目录,拓扑排序后批量加载         |
| `startPlugins()`               | 按拓扑顺序批量启动              |
| `stopPlugins()`                | 按拓扑逆序批量停止              |
| `unloadPlugins()`              | 按拓扑逆序批量卸载              |
| `reloadPlugin(id, newJarPath)` | 原子热重载                  |
| `getExtensions(Class<T>)`      | 获取某扩展点所有实现(按 order 升序) |
| `getExtension(Class<T>, id)`   | 按扩展 id 精确获取            |
| `subscribe / unsubscribe`      | 事件监听                   |

### PluginContext(插件侧)

| 方法                               | 说明                             |
|----------------------------------|--------------------------------|
| `getDescriptor()`                | 获取本插件元数据                       |
| `getPluginWorkDir()`             | 获取插件专属工作目录                     |
| `getProperty(key)`               | 读取配置(默认来源:`config.properties`) |
| `getExtensions(Class<T>)`        | 获取扩展点实现                        |
| `publishEvent(event)`            | 发布自定义事件                        |
| `subscribe(eventType, listener)` | 订阅事件(UNLOADED 时自动清理)           |

---

## 六、目录结构参考

```
sunsen/
├── sunsen-api/                ← 零依赖公共契约
│   └── src/main/java/...
├── sunsen-core/               ← 标准实现
│   ├── src/main/java/...
│   └── src/test/...           ← 集成测试 + 测试插件
├── sunsen-demo-plain/         ← 纯 Java 演示
│   ├── src/main/java/...      ← 宿主代码
│   └── src/main/plugin-src/   ← 内嵌插件源码
└── pom.xml
```

---

## 七、常见问题

**Q1:插件可以和宿主共用同一个数据库连接池吗？**

可以连接池类位于宿主 classpath,由父 ClassLoader 加载,插件通过双亲委派即可使用插件只需在 `plugin.json` 中不声明连接池相关包的
`packagePrefixes`

**Q2:插件能不能热重载后回滚到旧版本？**

框架本身不自动回滚若热重载失败(新 JAR 解析错误、类加载失败等),旧扩展已被注销宿主可通过监听 `PluginFailedEvent`
捕获异常,手动决策是否再次 `reloadPlugin` 回退到旧 JAR

**Q3:插件能否访问其他插件的私有类？**

不能`packagePrefixes` 机制确保插件自有类由专用 ClassLoader 加载,其他插件无法访问,除非显式声明依赖并通过依赖链共享
