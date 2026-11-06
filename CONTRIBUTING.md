# 贡献指南

## 开发环境

- JDK 17+
- 构建 Android 界面需要 Android SDK（设置 `ANDROID_HOME`）
- 仅开发 `spi` / `core` / `providers` 不需要 Android SDK

## 提交前检查

```bash
./gradlew :core:test          # 测试必须通过
./gradlew build               # 全量编译
```

## 新增供应商

1. 在 `providers/.../builtin/` 新建一个类，继承 `OpenAiCompatibleProvider`。
2. 填 `id`（小写、唯一）、`displayName`、`authKind`、`defaultBaseUrl`。
3. 在 `BuiltInProviders.all` 注册。
4. 给 `core` 补一个测试覆盖新的过滤或路由行为。

## 代码约定

- Kotlin 官方代码风格（`kotlin.code.style=official`）。
- 公共 API 必须有 KDoc，说明参数含义与可空性。
- 内核不引入新的第三方依赖；协议适配放在 `providers`。
- 测试要好读：断言失败信息里应当能直接看出期望与实际。
