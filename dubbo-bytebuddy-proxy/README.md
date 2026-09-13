# dubbo-bytebuddy-proxy
基于 [ByteBuddy](https://bytebuddy.net/) 实现的 Apache Dubbo `ProxyFactory` SPI 扩展，用字节码生成的方式替换 Dubbo 默认的 `javassist` / `jdk` 动态代理，实现**客户端接口代理**与**服务端方法调用分发**。


项目同时提供了两套实现思路：
- `Wrapper` 方案：直接手写字节码（ASM），为每个服务实现类生成一个 `invokeMethod` 分发器，无反射、无装箱猜测，性能最优（当前 `ProxyFactory` 默认使用）。
- `MethodCaller` 方案：ByteBuddy `MethodDelegation` + 反射 `Method` 缓存，代码更简洁直观，适合阅读和理解原理（保留作为对比实现）。

## 项目结构

```
dubbo-bytebuddy-proxy
├── pom.xml
└── src/main
    ├── java/com/zz/rpc/proxy
    │   ├── BytebuddyProxyFactory.java        # SPI 实现入口，Dubbo ProxyFactory
    │   ├── MethodCaller.java                 # 方法调用抽象接口
    │   ├── MethodCallerInterceptor.java      # 反射版方法分发（Method 缓存）
    │   └── wrapper
    │       ├── Wrapper.java                  # 抽象类 + 按 Class 缓存 Wrapper
    │       ├── WrapperImplementation.java    # ByteBuddy Implementation，桥接到 Appender
    │       ├── WrapperAppender.java          # 手写字节码：方法名/参数匹配 + 参数拆箱 + 返回值装箱
    │       └── BytebuddyWrapper.java         # 基于 MethodDelegation 生成 MethodCaller 的替代实现
    └── resources/META-INF/dubbo
        └── org.apache.dubbo.rpc.ProxyFactory # SPI 注册文件：bytebuddy=com.zz.rpc.proxy.BytebuddyProxyFactory
```

## 功能

### 1. 客户端代理（`getProxy`）

根据服务接口动态生成代理对象：

- `ByteBuddy.subclass(Object.class).implement(interface)` 生成接口实现类；
- 拦截接口声明的所有方法，委托给 Dubbo 的 `InvokerInvocationHandler`；
- 通过 `ClassLoadingStrategy.Default.WRAPPER` 加载，避免污染业务类加载器。

等价于 Dubbo 内置的 `JavassistProxyFactory` / `JdkProxyFactory` 的客户端行为，但由 ByteBuddy 完成字节码生成。

### 2. 服务端调用分发（`getInvoker`）

将真实服务对象包装成 `Invoker`，屏蔽底层方法调用细节：

- 通过 `Wrapper.getWrapper(type)` 为服务实现类生成专属的 `Wrapper` 子类；
- 生成的 `invokeMethod(instance, methodName, types, args)` 内部是一串 `if` 判断，直接匹配「方法名 + 参数个数」；
- 匹配成功后：`CHECKCAST` 转换引用类型参数、按需拆箱基本类型参数、直接 `INVOKEVIRTUAL` / `INVOKEINTERFACE` 调用真实方法、对返回值装箱（`void` 返回 `null`）；
- 未匹配到方法时抛出 `NoSuchMethodException`。

### 3. 性能相关设计
- **Wrapper 缓存**：`Wrapper.CACHE` 使用 `ConcurrentHashMap`，某个类只生成一次 Wrapper，后续直接复用实例；
- **零反射调用**：Wrapper 路径下方法调用完全是编译期生成的字节码，不经过 `Method.invoke`；
- **反射版缓存**：`MethodCallerInterceptor` 路径下使用 `METHOD_CACHE` 缓存 `Method`，避免重复查找；
- **合理的栈深度计算**：`WrapperAppender` 按 `long`/`double` 占 2 个槽位精确计算 `maxStack`，并补全 `StackMapFrame`，保证生成类通过字节码校验。

## 与 Dubbo 的接入
Dubbo 的 `ProxyFactory` 是一个 SPI 扩展点（接口全限定名 `org.apache.dubbo.rpc.ProxyFactory`），默认实现为 `javassist`（Dubbo 3.x）或 `jdk`。本项目通过 SPI 覆盖该扩展点：

1. 资源文件 `src/main/resources/META-INF/dubbo/org.apache.dubbo.rpc.ProxyFactory`：

   ```
   bytebuddy=com.zz.rpc.proxy.BytebuddyProxyFactory
   ```

   其中 `bytebuddy` 是扩展名（即后续在配置中使用的值），等号后面是全限定类名。

2. `BytebuddyProxyFactory` 继承 `org.apache.dubbo.rpc.proxy.AbstractProxyFactory`，只需实现两个方法：

   | 方法 | 作用 | 调用方 |
   | --- | --- | --- |
   | `getProxy(Invoker, Class[])` | 生成服务接口的代理对象 | Consumer（消费端） |
   | `getInvoker(T, Class, URL)` | 把服务实现包装为 `Invoker` | Provider（服务端） |

3. 依赖说明：`dubbo` 的 scope 为 `provided`，本项目只编译出扩展类并交给 Dubbo 运行时加载，不会传递 Dubbo 依赖。

> 原理：Dubbo 在启动时按扩展名加载 `ProxyFactory`，消费端用它把 `Invoker` 变成可注入的接口代理，服务端用它把实现类包装为 `Invoker` 供协议层调用。因此只要在消费端和服务端都把 `proxy` 配置为 `bytebuddy`，两侧就会走本项目的实现。

## 使用
### 1. 构建安装
```bash
mvn clean install
```

### 2. 引入依赖
由于未发布到中央仓库，需要先执行上一步安装到本地仓库，再在业务工程中引入：

```xml
<dependency>
    <groupId>com.zz.rpc.proxy</groupId>
    <artifactId>dubbo-bytebuddy-proxy</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### 3. 开启 bytebuddy 代理
**方式一：注解（推荐）**
```java
// 服务端
@DubboService(proxy = "bytebuddy")
public class UserServiceImpl implements UserService {
    // ...
}

// 消费端
@DubboReference(proxy = "bytebuddy")
private UserService userService;
```

**方式二：XML 配置**
```xml
<dubbo:service interface="com.example.UserService"
               ref="userService"
               proxy="bytebuddy"/>

<dubbo:reference id="userService"
                 interface="com.example.UserService"
                 proxy="bytebuddy"/>
```

**方式三：配置中心 / properties**
```properties
dubbo.provider.proxy=bytebuddy
dubbo.consumer.proxy=bytebuddy
```

### 4. 环境要求

| 项 | 版本     |
| --- |--------|
| JDK | 8+     |
| Dubbo | 3.3.5  |
| ByteBuddy | 1.17.6 |
| Maven | 3.6+   |

## 说明与注意事项

- 服务端 `Wrapper` 通过「方法名 + 参数个数」匹配方法，**同名同参数个数但参数类型不同的重载方法存在歧义**，如需严格支持重载，可扩展 `WrapperAppender` 加入参数类型校验。
- 生成的类通过 `ClassLoadingStrategy.Default.WRAPPER` 加载，不写入业务类加载器，卸载时随类加载器回收。
- `BytebuddyWrapper` / `MethodCaller` / `MethodCallerInterceptor` 为反射版对比实现，可在 `BytebuddyProxyFactory#getInvoker` 中按需切换。

## License

Apache License 2.0
