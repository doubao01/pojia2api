# ModelBridge 当前验收矩阵

来源：当前工作区源码、JVM 测试结果、GitHub Actions CI、3.10.0 修复版 APK 静态 dex 字符串。本文只记录已验证事实和明确边界，不把 CI 编译等同于真机视觉验收。

## 最近验证

- 本地 JVM 回归：core 83、providers 85，失败 0。
- GitHub Actions：run #35（到期前续期）、#36（任务/额度供应商协议）、#37（Loomy 任务积分）、#38（多供应商任务额度协议）、#39（验收文档）、#40（AStudio 积分）、#41（CI 验收记录）、#42（验收记录）、#43（账号页积分与每日签到）、#44（CI 验收记录）、#45（去客户端指纹）均为 `内核测试 success`、`构建 APK success`。
- 最近主分支提交：`18b72ee`。
- 3.10.0 修复版 APK：`/root/workspace/网关管家修复/网关管家-3.10.0-修复版.apk`，sha256 `749e6ccc5f0c909f114d6e1b0406fbcb823eae3db0ac5e60f6c539aa200e5ee0`。

## 功能矩阵

| 目标项 | 当前实现 | 验证证据 |
|---|---|---|
| 端口、监听、访问密钥 | `GatewaySettings`、`GatewayRunner`、`GatewayConfig`；局域网开关绑定回环地址；访问密钥启用网关鉴权 | core 测试 / CI APK |
| 调度：重试、超时、冷却、失败阈值 | `GatewayConfig` 接入整轮重试与 `withTimeout`；`HealthTracker` 负责失败阈值与冷却 | `GatewayRetryTest`、core 测试 |
| 日志上限、重置统计、清空日志 | `GatewayRunner` 可配置内存日志上限、清空与统计重置 | core 测试 / CI APK |
| 开机自启 | `BootReceiver` 与 manifest 广播 | CI APK；真机开机广播未验证 |
| 悬浮球 | `FloatingBallService` 显示共享网关运行状态、支持权限引导、尺寸更新、拖动边界 | CI APK；真机权限与悬浮窗未验证 |
| 导出/导入配置 | `ConfigTransfer` 处理设置、账号、策略；整包配置与账号文件解析分离 | core 测试 / CI APK |
| 账号持久化与凭据序列化 | `AccountPreferences` 保存成功后发布；`Credential` 密封类序列化；`authExpiresAtMillis` 记录 Loomy/OAuth 到期时间，过期账号不参与模型目录和路由 | `AccountPersistenceTest`、`CredentialExpiryTest` |
| 到期前刷新设备授权凭据 | `AccountCredentialRefresher` 将已保存账号映射回 Qoder/WorkBuddy 刷新协议，提前 5 分钟续期；轮换令牌、有效期和身份元数据原子写回；失败退避、并发编辑不覆盖；接进网关启动/周期维护、模型刷新与连通性检测，刷新后失效模型目录缓存 | `AccountCredentialRefresherTest`（8 项）、`CredentialRefreshCatalogTest`、CI APK；真实供应商刷新未验证 |
| 策略库与策略注入 | `PolicyMatcher` / `PolicyInjector` / `PrefsPolicyStore` / `PolicyScreen`；固定绑定与自动匹配 | `PolicyTest`、`PolicyGatewayTest`、CI APK |
| WebView Cookie 登录 | `WebLoginDialog` 读取 Cookie 并保存为 `Credential.Cookies` | CI APK；真实站点登录未验证 |
| Qoder/CodeBuddy 设备授权 | `DeviceLoginDialog` + `QoderDeviceAuth` / `WorkBuddyDeviceAuth` 轮询、取消、OAuth 元数据保存 | 41 个设备授权契约测试、CI APK；真实授权未验证 |
| Loomy 手机验证码 | `LoomySmsAuth` 两步 sendCode/checkCode 与 `LoomySmsDialog`；签名材料显式注入；登录成功保存 14 天会话有效期 | 5 个协议测试、`CredentialExpiryTest`、CI APK；生产签名材料未注入 |
| 手动添加、导入/导出账号 | `AccountEditDialog` 与 `AccountArchive`；兼容原版数组、WorkBuddy 单对象、secret 对象/字符串 | `AccountArchiveTest`、CI APK；用户已确认修正版导入文件不再报错 |
| 连通性检测、端口扫描 | `ViewModels.checkAccount` 与本机端口扫描 | CI APK |
| 首页统计、缓存命中率、token | `GatewayRunner` 使用真实上游 `Usage`，首页显示请求/成功率/耗时/缓存/token | core 测试 / CI APK |
| 首页复制地址、管理入口 | 复制本机/局域网地址；账号、策略、任务/额度入口 | CI APK |
| 任务/额度入口与页面 | `TaskQuotaScreen` 独立页面；按 3.10.0 文案显示一键签到、一键任务、任务列表、套餐/共享/加购额度；`AccountServiceRegistry` 已按静态契约接入多家供应商，逐账号汇总成功/失败/跳过，未接入供应商显示真实错误；未返回额度时明确显示查询失败，不伪造数据 | 9 组账号业务测试共 29 项、3.9.3 反编译契约、CI APK；真实供应商接口未验证 |
| 账号业务与逐账号积分/签到 | `providerGroups` 账号行按 CREDIT_REFRESH/CHECKIN 显示真实可用积分、额度详情、今日已签到/未签到，提供单账号「刷新积分」「签到」；本地签到状态按 Asia/Shanghai 自然日持久化 | `AccountDailyCheckinTest`（2 项）、账号业务测试、CI APK；真实服务端签到未验证 |
| 日志页清空按钮 | `LogScreen` 调用 `GatewayRunner.clearLog` | CI APK |
| 去客户端指纹 | `ClientFingerprintStripper` 按 3.10.0 dex 与 3.9.3 `z/g.java` 规则清理计费头、Claude/Codex/ZCode/DS Harness 身份句、模板句和空模板参数；身份句按原版替换为中性表述而不误删；网关记录规则级诊断，解析失败回退原文；模型和工具参数保留 | `ClientFingerprintStripperTest`（9 项）、CI APK；请求头指纹规则因静态规则表被 JADX 截断尚未完整复刻 |
| `@上游昵称` 定向路由 | 模型名后缀匹配供应商 ID/显示名/账号备注；只走指定上游，上游收到剥离后缀的模型 ID | `UpstreamHintRoutingTest`、CI APK |
| 多协议 HTTP 入口 | `/chat/completions`、`/responses`、`/messages` 与对应 `/v1` 路径；策略注入按协议写入 system / instructions / system | `WireProtocolGatewayTest`、CI APK |
| 设置页与关于页文案 | 按 3.10.0 dex 字符串对齐设置项、首页接入说明、免责声明、本机转发说明与作者署名语义 | `3.10.0-ui-audit.md`、CI APK |

## 仍未完成或受限范围

1. 已按 3.9.3 静态契约接入 WorkBuddy、Qoder、Loomy、小浣熊、CatPaw、Tabbit、Meoo、Trae、AStudio 的签到/任务/积分或额度接口，但尚未用真实账号抓包验证；AgentMore 的请求签名依赖原 APK 固定盐值，未复制该值，Dangbei 原版积分查询为空实现，Qoder 签到和部分复杂任务活动也因静态伪代码歧义未冒充已实现。当前页面对未接入或查询失败明确标注，不伪造数据。
2. Loomy 生产签名材料没有从原 APK 复制；构建期可通过 `MODELBRIDGE_LOOMY_APP_ID` / `MODELBRIDGE_LOOMY_ACCESS_KEY` / `MODELBRIDGE_LOOMY_SECRET` 注入，CI 可从 GitHub Secrets 传入。公开构建未配置时界面显示未配置，无法完成真实短信发送。Qoder 客户端标识也不从原 APK 复制，改为 `MODELBRIDGE_QODER_CLIENT_ID_CN` / `MODELBRIDGE_QODER_CLIENT_ID_GLOBAL` 构建期注入；未配置时设备授权明确提示缺少配置。
3. 3.10.0 真机逐屏视觉核对尚未完成。现有 3.10.0 证据来自 dex 字符串与 3.9.3 反编译源码，不能替代像素/交互验收。
4. WebView、设备授权、悬浮球、开机广播、文件选择器和配置恢复仍需真机操作验收；CI 只证明编译和 JVM 行为。
5. responses/messages 已实现基础请求转换、响应格式、策略注入和 SSE 基本事件；各厂商扩展字段尚未逐字段兼容。
6. 设备授权续期协议已由脚本化 HTTP 夹具验证并接入本地运行时；真实 Qoder/WorkBuddy 服务端接受所保存账号的 refresh token、以及长期后台续期仍需真机与生产凭据验收。
7. 原版 3.9.3/3.10.0 将「任务管理」与「账号管理」拆成两个页面：任务页只按 CHECKIN/TASKS 显示一键动作与任务列表，可用积分和当日签到状态在账号管理页逐账号显示。ModelBridge 的 `TaskQuotaScreen` 仍把两类信息合并，但供应商页账号行已补齐逐账号可用积分、额度详情、今日签到状态和单账号刷新/签到动作；独立页面结构与像素布局尚未对齐。

## 结论

当前项目已覆盖目标中可由源码、测试与 CI 验证的主要功能，并按 3.10.0 APK 静态证据补齐了账号导入格式、设置/关于/首页文案、去指纹、`@上游昵称`、多协议入口。由于仍存在明确的真机与生产依赖验收条件，不能宣称“完全 1:1 复刻完成”。
