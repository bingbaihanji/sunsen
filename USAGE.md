# Sunsen 插件框架 — 使用指南

> 本文档面向**宿主应用开发者**与**插件开发者**,帮助你在 5 分钟内上手 Sunsen

---

## 一、引入依赖

### 推荐：三模块结构

真实项目应将**扩展点接口单独抽成一个契约模块**（`{your-app}-api`），宿主和插件分别依赖它，互不依赖：

```
{your-app}-api    ← 只放扩展点接口和跨插件事件
       ↑                    ↑
{your-app}-host   {your-app}-plugins
（宿主业务逻辑）    （插件实现）
```

### Maven 各模块依赖配置

**`{your-app}-api`（契约模块）**——只依赖 `sunsen-api` 获取注解：

```xml
<dependency>
    <groupId>com.bingbaihanji</groupId>
    <artifactId>sunsen-api</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

**`{your-app}-host`（宿主应用）**——依赖契约模块 + 框架运行时：

```xml
<!-- 扩展点契约接口 -->
<dependency>
    <groupId>com.example</groupId>
    <artifactId>{your-app}-api</artifactId>
    <version>${project.version}</version>
</dependency>
<!-- 插件框架运行时（含 sunsen-core） -->
<dependency>
    <groupId>com.bingbaihanji</groupId>
    <artifactId>sunsen-server</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

**`{your-app}-plugins`（插件）**——只依赖契约模块 + 插件开发工具包：

```xml
<!-- 扩展点契约接口（provided：运行时由宿主 ClassLoader 提供） -->
<dependency>
    <groupId>com.example</groupId>
    <artifactId>{your-app}-api</artifactId>
    <version>${project.version}</version>
    <scope>provided</scope>
</dependency>
<!-- 插件框架工具（provided：运行时由宿主 ClassLoader 提供） -->
<dependency>
    <groupId>com.bingbaihanji</groupId>
    <artifactId>sunsen-api</artifactId>
    <version>1.0-SNAPSHOT</version>
    <scope>provided</scope>
</dependency>
```

> **为什么插件不能依赖宿主模块？**  
> 宿主模块包含业务实现代码，插件依赖它会形成循环耦合，且宿主每次变更都会影响所有插件。
> 将接口提取到独立的 `{app}-api` 模块后，宿主和插件共同依赖"契约"，插件开发者甚至不需要宿主源码，
> 只需要这个轻量的接口包。参见 `sunsen-demo-plain/demo-api` 的完整示范。

### 最低环境要求

- **JDK 21+**(`sunsen-core` 零第三方依赖,仅 `sunsen-server` 依赖 `jackson-databind` 解析 `plugin.json`)

---

## 二、宿主应用接入(5 分钟速成)

### 2.1 定义扩展点

扩展点是**宿主与插件之间的契约接口**，应定义在独立的 `{app}-api` 模块中，并标注 `@ExtensionPoint`:

```java
// 在 {your-app}-api 模块中定义，而非宿主业务模块
package com.example.app.api;

import com.bingbaihanji.sunsen.api.annotation.ExtensionPoint;

@ExtensionPoint(id = "greeting", description = "问候语扩展点")
public interface Greeter {
    String greet(String name);
}
```

**关键规则**:

- 扩展点接口必须位于**宿主 classpath**（父 ClassLoader 可见），确保所有插件共享同一类型；推荐放在独立的 `{app}-api` 模块中
- 扩展点接口**不能**出现在任何插件的 `packagePrefixes` 范围内，否则插件加载时会报前缀冲突错误
- `id` 为空时,框架自动使用接口全限定名作为 key
- `allowMultiple = false` 时,框架会校验**同一插件内**该扩展点的实现数量恰好为 1,否则拒绝加载;跨插件的多个实现仍允许
- `sole = true` 时,框架确保**整个系统**中只有一个插件的实现被注册,冲突时保留 `order` 最小(优先级最高)的一个,并打印警告

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

**使用自定义事件总线**(异步模式):

```java
Executor executor = Executors.newCachedThreadPool();
DefaultPluginManager manager = new DefaultPluginManager(new PluginEventBus(executor));
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

**订阅父类事件**:订阅 `PluginEvent.class` 即可接收所有生命周期事件

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

### 3.2 使用 @Plugin 注解（推荐）

在插件入口类上标注 `@Plugin`,编译时自动生成 `META-INF/plugin.json`,无需手动维护 JSON.

```java
package com.example.plugin.hello;

import com.bingbaihanji.sunsen.api.annotation.Plugin;
import com.bingbaihanji.sunsen.api.support.AbstractPlugin;

@Plugin(
        id = "com.example.plugin.hello",
        name = "Hello Plugin",
        description = "提供英语问候能力",
        version = "1.0.0",
        packagePrefixes = "com.example.plugin.hello",
        dependencies = @Plugin.Dependency(
                id = "com.example.plugin.base",
                version = ">=1.0.0 <2.0.0"
        ),
        permissions = {"file:read", "file:write"}
)
public class HelloPlugin extends AbstractPlugin {
    // ...
}
```

**`@Plugin` 属性说明**:

| 属性                | 必填 | 说明                                       |
|-------------------|----|------------------------------------------|
| `id`              | ✅  | 全局唯一标识,建议反向域名格式                          |
| `name`            | ✅  | 展示名称                                     |
| `version`         | ✅  | SemVer 格式,如 `1.2.0`                      |
| `packagePrefixes` | ✅  | 插件私有包前缀,至少提供一个                           |
| `description`     | ❌  | 插件描述                                     |
| `apiVersion`      | ❌  | 默认空,自动使用框架当前 `SunsenVersion.API_VERSION` |
| `mainClass`       | ❌  | 默认空,自动推断为标注类本身                           |
| `dependencies`    | ❌  | 依赖插件列表,嵌套 `@Plugin.Dependency`           |
| `permissions`     | ❌  | 运行时权限声明                                  |
| `vendor`          | ❌  | 厂商信息,嵌套 `@Plugin.Vendor`                 |

**兼容手动 plugin.json**:若 `src/main/resources/META-INF/plugin.json` 已存在,Processor 自动跳过,以手动文件为准.

### 3.3 手动编写 plugin.json（备选）

若不想使用注解,可手动在 `src/main/resources/META-INF/plugin.json` 中维护:

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

| 字段                | 必填 | 说明                                                         |
|-------------------|----|------------------------------------------------------------|
| `id`              | ✅  | 全局唯一标识,建议反向域名格式                                            |
| `name`            | ✅  | 展示名称                                                       |
| `version`         | ✅  | SemVer 格式,如 `1.2.0`                                        |
| `apiVersion`      | ✅  | 编译时依赖的 Sunsen API 主版本号,需与框架 `SunsenVersion.API_VERSION` 一致 |
| `mainClass`       | ✅  | 实现 `Plugin` 接口的入口类全限定名                                     |
| `packagePrefixes` | ✅  | 插件私有包前缀,类隔离的核心配置                                           |
| `dependencies`    | ❌  | 依赖插件列表,支持语义化版本约束                                           |
| `permissions`     | ❌  | 运行时权限声明(由宿主在 PluginContext 中拦截)                            |
| `vendor`          | ❌  | 作者/厂商信息,格式为 `{ "name": "...", "url": "..." }`              |

**版本约束表达式**:

| 表达式              | 含义                         |
|------------------|----------------------------|
| `1.2.0`          | 精确版本                       |
| `=1.2.0`         | 精确版本                       |
| `>1.0.0`         | 大于指定版本                     |
| `<2.0.0`         | 小于指定版本                     |
| `>=1.0.0`        | 大于等于                       |
| `<=2.0.0`        | 小于等于                       |
| `!=1.0.0`        | 不等于                        |
| `>=1.0.0 <2.0.0` | 范围约束                       |
| `^1.2.0`         | 兼容版本(等价于 `>=1.2.0 <2.0.0`) |
| `~1.2.0`         | 近似版本(等价于 `>=1.2.0 <1.3.0`) |

### 3.4 实现 Plugin 接口

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

        // 读取插件工作目录下的 config.properties
        String timeout = context.getProperty("timeout", "5000");
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

**插件配置**:框架会自动读取插件工作目录(`pluginsDir/<pluginId>/`)下的 `config.properties` 文件,通过
`PluginContext.getProperty(key)` 访问

### 3.5 实现扩展

```java
package com.example.plugin.hello;

import com.bingbaihanji.sunsen.api.annotation.Extension;
// 依赖 {your-app}-api 模块，而非宿主业务模块
import com.example.app.api.Greeter;

@Extension(order = 10, description = "英语问候")
public class EnglishGreeter implements Greeter {
    @Override
    public String greet(String name) {
        return "Hello, " + name + "!";
    }
}
```

**`@Extension` 属性说明**:

| 属性            | 默认值     | 说明                                       |
|---------------|---------|------------------------------------------|
| `id`          | `类全限定名` | 扩展唯一标识                                   |
| `order`       | `0`     | 排序权重,值越小越靠前                              |
| `singleton`   | `true`  | 是否单例。`false` 时每次 `getExtensions()` 创建新实例 |
| `description` | `""`    | 扩展描述                                     |

**`allowMultiple = false` 扩展点**:若扩展点接口标注了 `allowMultiple = false`,同一插件内不能有多个实现类,否则加载时报错

### 3.6 打包插件

插件最终产物是一个包含 `META-INF/plugin.json` 的 **标准 JAR 文件**

- 使用 `@Plugin` 注解时,`plugin.json` 由编译器自动生成到编译输出目录,`maven-jar-plugin` 会将其自动打包入 JAR
- 手动编写 `plugin.json` 时,将其放在 `src/main/resources/META-INF/plugin.json` 即可

**Maven 示例**:

```xml

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-jar-plugin</artifactId>
            <!-- 使用 @Plugin 注解时无需额外配置,plugin.json 编译期自动生成 -->
        </plugin>
    </plugins>
</build>
```

将打包后的 JAR 放入宿主指定的 `plugins/` 目录即可

**参考 Demo 的 antrun 打包方式**:

`demo-plugins` 子模块使用 `maven-antrun-plugin` 在 `prepare-package` 阶段将 `src/main/java/` 下的插件源码分别编译并打包为
JAR，放入 `demo-plugins/target/plugins/`。各插件通过 `includes` 模式按包名隔离编译。完整配置可参考 `sunsen-demo-plain/demo-plugins/pom.xml`

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
7. 更新依赖该插件的其他插件的 ClassLoader 引用
8. 释放写锁
9. 新插件 `onStart()` → 状态 `ACTIVE`

**限制**:若插件被其他插件依赖,`reloadPlugin()` 会直接抛出异常。需先卸载依赖方,或避免对需要频繁热重载的插件建立依赖关系

### 4.2 插件间依赖

`plugin.json` 中声明依赖后,框架自动完成:

- **存在性校验**:非 optional 依赖必须存在
- **版本匹配**:支持范围、`^`、`~`、精确版本及 `=`、`>`、`<`、`!=` 等操作符
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

自定义 `PluginContext` 后,需在创建 `DefaultPluginManager` 时通过继承并重写 `loadSinglePlugin` 中 `DefaultPluginContext`
的构造逻辑来替换(当前版本标准入口为内部方法,建议通过继承 `DefaultPluginManager` 并重写相关方法实现)

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
| `reloadPlugin(id, newJarPath)` | 原子热重载(被依赖时拒绝)          |
| `getExtensions(Class<T>)`      | 获取某扩展点所有实现(按 order 升序) |
| `getExtension(Class<T>, id)`   | 按扩展 id 精确获取            |
| `subscribe / unsubscribe`      | 事件监听                   |
| `setExtensionRegistrar`        | 设置扩展生命周期钩子             |

### PluginContext(插件侧)

| 方法                                 | 说明                           |
|------------------------------------|------------------------------|
| `getDescriptor()`                  | 获取本插件元数据(record 类型)          |
| `getPluginWorkDir()`               | 获取插件专属工作目录                   |
| `getProperty(key)`                 | 读取配置(来源:`config.properties`) |
| `getProperty(key, defaultValue)`   | 读取配置,带默认值                    |
| `getExtensions(Class<T>)`          | 获取扩展点实现                      |
| `publishEvent(event)`              | 发布自定义事件                      |
| `subscribe(eventType, listener)`   | 订阅事件(UNLOADED 时自动清理)         |
| `unsubscribe(eventType, listener)` | 取消订阅                         |
| `getPluginManager()`               | 获取 PluginManager             |

---

## 六、目录结构参考

```
sunsen/
├── sunsen-core/                       ← 零依赖公共契约
│   └── src/main/java/...
├── sunsen-api/                        ← 插件开发工具包
│   └── src/main/java/...
├── sunsen-server/                     ← 标准实现
│   ├── src/main/java/...
│   └── src/test/...                   ← 集成测试 + 测试插件
├── sunsen-demo-plain/                 ← 纯 Java 演示（聚合模块）
│   ├── demo-api/                      ← ① 扩展点契约接口（推荐模式示范）
│   │   └── src/main/java/...          ← Greeter / GreetingFormatter / GreetingEvent
│   ├── demo-plugins/                  ← ② 三个演示插件
│   │   └── src/main/java/com/.../     ← hello / world / scheduler
│   └── demo-host/                     ← ③ 宿主应用
│       └── src/main/java/...          ← PlainDemoApp
└── pom.xml
```

**三模块依赖关系**：

```
demo-host  ──依赖→  demo-api  ←──依赖──  demo-plugins
demo-host  ──依赖→  sunsen-server
demo-plugins ──依赖→ sunsen-api
```

宿主和插件**互不依赖**，共同依赖 `demo-api` 契约包。

### 运行测试

```bash
mvn test -pl sunsen-server
```

### 运行 Demo

```bash
mvn install
mvn exec:java -pl sunsen-demo-plain/demo-host
```

---

## 七、常见问题

**Q1:插件可以和宿主共用同一个数据库连接池吗？**

可以。连接池类位于宿主 classpath,由父 ClassLoader 加载,插件通过双亲委派即可使用。插件只需在 `plugin.json` 中不声明连接池相关包的
`packagePrefixes`

**Q2:插件能不能热重载后回滚到旧版本？**

框架本身不自动回滚。若热重载失败(新 JAR 解析错误、类加载失败等),旧扩展已被注销。宿主可通过监听 `PluginFailedEvent`
捕获异常,手动决策是否再次 `reloadPlugin` 回退到旧 JAR

**Q3:插件能否访问其他插件的私有类？**

不能。`packagePrefixes` 机制确保插件自有类由专用 ClassLoader 加载,其他插件无法访问,除非显式声明依赖并通过依赖链共享

**Q4:插件的 `apiVersion` 与框架版本不一致会怎样？**

加载时会抛出 `PluginLoadException`。`SunsenVersion.API_VERSION` 当前为 `"1.0"`,插件的 `plugin.json` 中 `apiVersion`
必须严格匹配

**Q5:多个插件可以实现同一个扩展点吗？**

可以,除非扩展点标注了 `allowMultiple = false`。框架会收集所有插件的实现,按 `@Extension.order` 排序后返回
