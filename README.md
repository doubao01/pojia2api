# ModelBridge

把多个 AI 服务聚合成一个 **OpenAI 兼容的本地 API 网关**。

在手机或任何能跑 JVM 的设备上启动后，局域网内的应用就能用统一的
`/v1/chat/completions`、`/v1/models` 接口访问背后各家模型，
不需要每家都对接一遍。

## 为什么做这个

原项目（网关管家 / pojia2api）功能很强，但混淆严重、没有测试、
`/v1/models` 不过滤停用账号。本项目重写了整套架构，重点解决三件事：

1. **可读**：分层清晰，核心逻辑零第三方 HTTP 依赖，单文件可读。
2. **可测**：内核 26 个单元测试覆盖路由、熔断、目录过滤、错误映射。
3. **可扩展**：接入新供应商只需实现一个 `Provider`，不动内核。

## 模块

```
modelbridge/
├── spi        供应商契约（Provider、凭证、对话模型、HTTP 抽象）
├── core       网关内核（HTTP 服务、路由、熔断、模型目录、OpenAI 协议）
├── providers  16 个内置供应商 + OpenAI 兼容基类
└── app        Android 界面（需要 Android SDK）
```

`spi`、`core`、`providers` 是纯 JVM 模块，**不依赖 Android**，
可以在任何环境编译、测试、甚至跑在服务端。

## 性能与进程管理

### 模型目录缓存
`ModelCatalog` 内部持有一个 `Cache<K,V>`（TTL 60 秒），同一个供应商/账号合并后
的结果会缓存。这样频繁刷新主界面不会重复穿透所有供应商调用上游。

### 前台服务
`GatewayService` 使用前台服务 + 通知，防止系统回收进程。
`AccountPreferences` 基于 SharedPreferences 持久化账号，
重启后配置不丢失。
`ServiceManager` 包装了启动/停止逻辑。

## 构建

需要 JDK 17+。

```bash
# 编译三个核心模块
./gradlew :spi:compileKotlin :core:compileKotlin :providers:compileKotlin

# 运行测试
./gradlew :core:test

# 构建 Android APK（需要 Android SDK）
export ANDROID_HOME=/path/to/sdk
./gradlew :app:assembleDebug
```

## 关于 ARM64 环境

`spi`、`core`、`providers` 是纯 JVM 模块，任何平台都能编译与测试。
`app` 模块的最终打包依赖 `aapt2`，它只有 x86_64 原生实现；
在 ARM64 机器（如 proot 手机环境）上无法完成资源打包，
但 Kotlin 源码编译不受影响。请在 x86_64 机器上打 APK。

## 核心概念

### Provider（供应商）

代表一个上游服务的接入方式。SPI 是它唯一的契约：

```kotlin
class MyProvider : OpenAiCompatibleProvider() {
    override val id = "mine"
    override val displayName = "我的服务"
    override val authKind = AuthKind.ApiKey
    override val defaultBaseUrl = "https://api.example.com/v1"
}
```

绝大多数上游是 OpenAI 兼容的，继承 `OpenAiCompatibleProvider`
后只需填四个字段。协议差异通过覆盖 `authHeaders`、
`parseModels`、`extraChatFields` 等钩子处理。

### Account（账号）

一个已配置的上游账号，含凭据、启停状态、优先级、模型级开关。
**停用的账号不贡献模型，也不参与路由**——这是设计上的硬规则，
内核里的 `ModelCatalog` 与 `Router` 都按此过滤。

### 路由与熔断

`Router` 把模型 ID 解析到一组候选账号，按优先级排序；
`HealthTracker` 在连续失败后让账号进入冷却，成功即恢复。
一次请求会依次尝试候选账号，全部失败才向客户端返回 503。

## 内置供应商

与 3.10.0 对齐的 14 家：`zhipu`（智谱）、`deepseek`、`astudio`（讯飞星火）、
`agentmore`、`wenxin`（文心）、`tabbit`、`meoo`、`dangbei`（当贝）、
`catpaw`（美团）、`codebuddy`（腾讯）、`raccoon`（小浣熊）、
`trae`、`qoder`、`loomy`。

> 相对 3.9.x 的变化：移除 `opencode`、`runable`；
> `xiaohuanxiong` 更名为 `raccoon`。

各供应商的接入方式与所需登录形态见
`providers/.../builtin/` 下各文件的 KDoc。

> 说明：其中部分服务在原应用里通过 WebView 会话（Cookie）接入。
> 本项目优先采用它们的 OpenAI 兼容 API 路径；少数只提供会话登录
> 的厂商保留了 `AuthKind.OAuth`/`Cookie`，接入前需先取得凭据。

## 目录

- [SPI 契约](spi/src/main/kotlin/io/modelbridge/spi/Provider.kt)
- [网关内核](core/src/main/kotlin/io/modelbridge/core/Gateway.kt)
- [HTTP 服务](core/src/main/kotlin/io/modelbridge/core/http/HttpServer.kt)
- [单元测试](core/src/test/kotlin/io/modelbridge/core/)

## 许可证

Apache-2.0
