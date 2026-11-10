# 任务与额度协议验收

## 静态证据

来源为 `/root/workspace/源码还原/网关管家-3.9.3/sources` 的反编译 Java，以及 3.10.0 修复版 dex 字符串。只提取公开路径、字段与凭据类型；原 APK 中的 token、key、客户端标识、设备指纹和签名材料均未复制进仓库，也未写入本文。

- 能力枚举：`p009j/z.java:24-32`，`CREDIT_REFRESH` / `CHECKIN` / `TASKS` / `POINTS_BILLS`。
- 任务页：`dev/pojia2api/ui/TaskCenterActivity.java:87-89` 只显示带 CHECKIN/TASKS 的供应商；动作字符串 `checkin` / `tasks` 见 `:199`、`:203`。
- 逐账号批量：`C/E.java:415-724` 过滤停用账号、自动续期、汇总成功/失败/跳过；额度实际在 `AccountCenterActivity.java:1088-1203` 按账号展示。
- WorkBuddy：`n/d.java:7-10`、`p008i/a.java:8277-8288` 定义国内/国际端点；签到 `:1271`，成长任务 `:1150`，一键任务 `:441-749`，额度 `:969-1036`；请求头 `:2491-2515`。
- Qoder：`p017t/e.java:536` 的 `/api/v2/quota/usage`；`p017t/s.java:2966-2978` 解析 `userQuota` / `addOnQuota` / 共享额度字段；额度数字别名见 `:33-57`。
- 其它已核对但未接入的契约：Tabbit（`p020w/f.java`）、小浣熊（`p018u/c.java`）、CatPaw/catx（`p008i/a.java:4957`）、Loomy（`p015q/m.java`、`p015q/h.java`）、Trae（`p021x/y.java`）、Meoo（`r/d.java`）。供应商归属以 `p008i/a.java:282-455` 注册表为准。

## ModelBridge 实现

- `AccountServiceRegistry`：统一暴露额度、签到、任务领取、任务列表；未注册供应商和能力外动作返回明确的不支持结果。
- `WorkBuddyAccountService`：
  - 额度 `POST /v2/billing/meter/get-user-resource`，请求体按原版 `P()` 构造，解析 `Response.Data.Accounts[].CycleCapacitySize/CycleCapacityRemain/CapacityRemain`。
  - 签到 `POST /v2/billing/meter/daily-checkin`，识别「今日已签到」与 `code == 0`。
  - 任务 `GET /v2/activity/growth/tasks`，映射进度、锁定、已领取、奖励积分。
  - 一键任务领取先请求礼包/补偿接口，再按成长任务状态领取或接取；只有上游 2xx 且返回可识别结果才计入成功。
  - 国内版与企业账号差异按原版规则处理；国际版与有企业 ID 的账号明确返回不支持。
- `QoderAccountService`：查询套餐、加购、共享额度，保留原始 used / remaining / total 汇总；Qoder 签到涉及额外设备派生头和多个活动接口，静态伪代码条件存在歧义，本轮不冒充已实现。
- `TraeAccountService`：国内/国际额度包列表、签到状态和领取；固定版本与设备上下文由宿主注入，不从原 APK 复制。
- `TabbitAccountService`：签到状态/每日签到、套餐额度百分比换算；固定浏览器识别和请求上下文由宿主注入。
- `MeooAccountService`：Cookie 登录态下的每日登录赠送状态和积分余额。
- `RaccoonAccountService` / `CatpawAccountService`：小浣熊 Bearer 积分余额、CatPaw X-Auth-Token 积分余额。
- `AStudioAccountService`：用已保存 API Key 查询 `/points/balance` 与 `/membership/me`，汇总普通积分、星火积分和套餐名；版本/客户端上下文由宿主注入。
- UI：首页「任务管理」与「账号管理」分别进入独立页面模式；任务管理进入时刷新批量任务/额度，账号管理进入时逐账号刷新积分/签到；按钮按供应商逐账号执行并汇总「成功/失败/跳过」；任务列表显示真实状态与奖励；额度按套餐/共享/加购/积分分类汇总；失败显示上游错误或「查询失败」，不用本地请求数和 token 用量伪造额度。

## 本地验证

- `AccountServicesTest` 8 项、`LoomyAccountServiceTest` 4 项、`BearerCreditServicesTest` 4 项、`TabbitAccountServiceTest` 4 项、`MeooAccountServiceTest` 3 项、`TraeAccountServiceTest` 4 项、`AStudioAccountServiceTest` 2 项：覆盖已接入供应商的请求头、路径、响应解析、签到与任务状态、失败隔离。
- 未实现边界：AgentMore 的 `X-Sign` 依赖原 APK 固定盐值，不从反编译源码复制，因此未生成不可信签名请求；Dangbei 原版 `c(account)` 直接返回 `null`，保持明确不支持。
- `./gradlew :core:test :providers:test --offline`：core 84、providers 85，失败 0。
- `AccountDailyCheckinTest` 2 项：按 Asia/Shanghai 自然日和账号 ID 生成签到键，验证同日幂等、跨日清除、账号隔离。
- 供应商页账号行按 `CREDIT_REFRESH` / `CHECKIN` 能力展示真实可用积分、额度详情、今日已签到/未签到，并提供单账号「刷新积分」「签到」；签到状态持久化在 `DailyCheckinStore`，不把本地缓存冒充服务端记录。
- CI 负责 Android `:app:assembleDebug` 编译与打包；JVM 测试不覆盖 Android UI 渲染。

## 边界

- 3.9.3 为 JADX 反编译源码，部分方法存在寄存器/控制流还原警告；3.10.0 只提供 dex 字符串证据。请求与响应字段已按静态源码实现，但真实服务端是否接受请求、风控头、限频、幂等和额度单位仍需真机抓包验证。
- `AccountCenterModeContractTest`：静态验证首页两个入口分别对应任务管理/账号管理，且两个模式渲染到不同子页。
- 原版把「任务管理」和「账号管理」拆成两个页面；ModelBridge 现已按两个独立页面模式拆分并复用同一真实数据层，剩余差异是页面分组和像素布局的真机核对。
- 未逐家实现的供应商在页面中显示不支持或查询失败；不会生成虚构额度。
