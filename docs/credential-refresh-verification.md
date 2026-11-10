# 设备授权凭据续期验证

## 已实现

- `AccountCredentialRefresher` 扫描已启用、带 refresh token、带有效期的 OAuth 账号。
- Qoder / WorkBuddy 分别复用 `QoderDeviceAuth.refresh` 与 `WorkBuddyDeviceAuth.refresh`，提前 5 分钟续期。
- 成功返回的新 access token、refresh token、有效期、区域、用户 ID、企业 ID、域名和昵称写回账号仓储；`authExpiresAtMillis` 同步更新，续期后的账号继续参与模型目录与路由。
- 失败至少退避 60 秒，供应商指定的退避更长时取更长时间；并发编辑账号时，在途刷新结果不会覆盖用户的新凭据。
- 刷新后通过 `Gateway.refreshCatalog(force = true)` 失效供应商模型目录缓存，避免旧令牌拉取的结果继续停留。
- 运行时接线：应用启动刷新模型、网关服务启动、常驻网关每 5 分钟维护、手动「拉取模型」和「检测连通性」。

## 本地验证

- `AccountCredentialRefresherTest`：到期账号续期与落库、省略 TTL 时保留旧有效期、元数据映射、跳过规则、失败退避、手动重试、并发编辑保护、上游异常计数，共 8 项。
- `CredentialRefreshCatalogTest`：凭据更换后，失效缓存迫使供应商以新凭据重拉模型。
- `./gradlew :core:test :providers:test --offline`：core 78、providers 54，失败 0。
- CI 负责 Android `:app:assembleDebug` 编译与打包；JVM 测试不覆盖 Android UI。

## 仍未验证

- 真实 Qoder / WorkBuddy 服务接受已保存账号的 refresh token。
- 真机长期挂机、进程回收后再次拉起、开机自启时的续期链路。
- 原版 3.10.0 各供应商实际的刷新间隔与失败提示文案；当前 5 分钟维护周期是本项目的保守实现。

本页只记录源码、脚本化 HTTP 与 JVM 测试可证明的行为，不把 CI 编译等同于真实账号验收。
