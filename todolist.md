# TodoList — 未了结事项累积清单

> - 每轮会话把用户提出的「需要修改 / 新增 / 优化」逐项归纳**追加**到本文件；
> - 后续轮次用户确认某项「已修复 / 完成 / 结束」→ **删除**对应条目；
> - 用户未确认完成的事项**一直累积保留**，作为跨轮次跟踪清单；
> - 每轮回复格式：先「本轮改动总结」（逐项归纳），后「生效提示」表格。

---

## 2026-11-05 · 五项需求（已对时 + 全部落地，v0.2.51）

> 本轮五项需求已**全部按建议默认值落地实现**（客户端/服务端/管理后台三端 + 文档）。
> 术语遵循 `docs/architecture/terminology.md`（客户端 / 服务端 / 管理后台）。
> 实现要点见下方各任务「✅ 已实现」段；详细回执契约见 `docs/api/commands/system.md`。

### ✅任务一：管理后台新增「无障碍」命令（开启/关闭/检查状态）✅

- 客户端 `BridgeAccessibilityService` 新增 `canWriteSecureSettings()`（探测 WRITE_SECURE_SETTINGS），
  `enableInSettings`/`disableInSettings` 改为返回布尔结果（可如实回执）。
- 新增命令 `accessibility_status`/`accessibility_enable`/`accessibility_disable`（三步法 + 幂等守卫）。
- 管理后台 `ACCESSIBILITY_STATE_RULES` 状态联动（unknown 禁用 + 强制下发二次确认）。
- 关闭无障碍敏感操作已纳入状态联动 + 二次确认（采纳建议默认值「是」）。

### ✅任务二：首次安装权限盘点 + 管理后台「ADB 一键全量授权」✅

- 盘点出三类权限：`pm grant` 危险权限（41 项，定位/相机/麦克风/蓝牙/通讯录/短信/通话/日历/体感/媒体/通知）、
  `pm grant` 特殊权限（WRITE_SECURE_SETTINGS）、`appops` 特殊路径（6 项：写设置/悬浮窗/装未知应用/使用情况/管理存储/忽略电池优化）。
- 服务端 `adb_helper.grant_all_permissions()` + 端点 `/adb-grant-all`。
- 设备页新增「ADB 一键授权」按钮 + 弹窗（分组展示 granted/appops/manual 三栏结果）。
- 「已连 ADB 设备列表」复用现有 `getDeviceNetInfo`（无需另建）。

### 任务三：无障碍协助安装新版本 ✅

- `check_update` 命令 `mode` 新增 `assist_install`（采纳建议默认值）。
- `UpdateManager.checkAndInstall` 加 `assistInstall` 参数 + `autoClickInstall`（自动点击安装页
  「安装/继续/下一步/确定/Install/Next/OK」按钮，10 次 + 30s 护栏）。
- 前置校验：无无障碍时先尝试 `enableInSettings()` 自动开启，无法开启则回执引导手动/ADB 开。

### ✅任务四：管理后台控制「微信离开后立即结束」开关 ✅

- `WechatRuntime` 加 `isPageActive()`/`isStopped()` 只读访问器。
- 新增命令 `wechat_release_status`/`wechat_release_enable`/`wechat_release_disable`。
- 关闭开关按「pageActive + 亮屏」双条件决定是否立即 `stopAll`（采纳建议默认值「双条件」）。
- 管理后台 `WECHAT_RELEASE_STATE_RULES` 状态联动。

### 任务五：无界面模式（儿童锁 / 防误触）✅

- 采用「方案 B 全屏遮挡层」（采纳建议默认值）：`UiLockController`（Compose state + Prefs 持久化）
  + `MainScreen` 顶层 `UiLockOverlay`（锁图标 + 提示，BackHandler 吞返回键防误触）。
- 新增命令 `ui_lock_status`/`ui_lock_start`/`ui_lock_stop`。
- 页面处置分层：销售/短信/音频中继/快递扫码 → 隐藏+释放（组合销毁自动 stop）；
  组网 → 不动；微信 → 跟随 release_on_leave；主 WS+保活 → 不动（硬约束）。
- 底部导航/整屏遮罩覆盖（采纳建议默认值「是」）；设备端无退出入口仅后台解除（采纳建议「是」）。

---

## 生效提示

| 任务 | 状态 | 说明 |
|---|---|---|
| 任务一 无障碍命令 | ✅ 已实现 | accessibility_status/enable/disable + 状态联动 |
| 任务二 权限盘点 + 一键授权 | ✅ 已实现 | grant_all_permissions + 设备页「ADB 一键授权」 |
| 任务三 无障碍协助安装 | ✅ 已实现 | check_update mode=assist_install + 自动点击 |
| 任务四 微信离开即释放开关 | ✅ 已实现 | wechat_release_enable/disable/status + 双条件释放 |
| 任务五 无界面模式 | ✅ 已实现 | ui_lock_start/stop/status + 方案B遮挡层 |

> 编译校验：客户端 `:app:compileDebugKotlin` 通过、服务端 Python 语法通过、
> 前端 `vue-tsc -b` 通过。改 command_catalog.py 后需重启 central-server 生效。

---

## 2026-11-05 · 新增：设备统计命令（电量 + 流量，已对时，待落地）

> 用户拍板三点：① 活跃时长用系统直读（不持续计算）；② 本月流量接受授权、但不持续计算；
> ③ 电量+流量合并成**一条命令卡片**。核心原则：**全部「下发时读一次」，零持续后台开销。**

### 需求定稿（get_device_stats，一条命令卡片）

**电量部分**（沿用现有 `BatteryReporter.read`，不新增持续计算）
- 当前电量百分比、充电状态/方式、温度、电压、健康度（已有，直接复用）。
- 追加「本 App 活跃时长」：`UsageStatsManager.queryUsageStats` 系统已归档的
  `totalTimeInForeground` / `totalTimeVisible`，**下发时直读**（复用 `ForegroundAppReader.hasUsageAccess`
  的权限探测；未授权则回执「未授予使用情况访问权，无法统计活跃时长」）。
  → 系统主动记录，**无需持续标记**。

**流量部分**（全新，全部系统直读，零持续计算）
- 本 App 流量（开机累计）：`TrafficStats.getUidRxBytes/TxBytes(myUid)`，零权限精确到字节。
  WiFi/移动分开：uid 级不直接分接口，用「整机 WiFi - 整机移动」比例不可靠 → 改用
  `NetworkStatsManager.queryDetailsForUid`（API 23+，按 NetworkTemplate 分 WiFi/MOBILE），
  需「使用情况访问权」；未授权回退仅给「本 App 总流量（不分接口）」。
- 本 App 本月流量：`NetworkStatsManager.querySummary(MONTHLY, ...)`，系统按自然月归档，
  **直读无需持续记录**；未授权回执提示。
- 整机流量（开机累计）：`TrafficStats.getMobileRxBytes/TxBytes` + `getTotalRxBytes`（WiFi=总-移动），零权限。

**明确放弃**：「APP 耗电 mAh」精确值无官方 API（需 shell/dumpsys batterystats），
本轮**不做**，以「活跃时长」作活跃度参考替代（用户已接受）。

### 待落地清单
1. 客户端新增 `DeviceStatsReporter`（data 包）：聚合 BatteryReporter + UsageStats（活跃时长）+
   TrafficStats（开机累计）+ NetworkStatsManager（本月/分接口，需 PACKAGE_USAGE_STATS）。
2. `Command.kt` 加 `DEVICE_STATS = "get_device_stats"` + `CommandModule` 注册执行器
   `executeGetDeviceStats`（复用现有 executeGetBattery/executeGetNetwork 范式）。
3. 服务端 `command_catalog.py` 加 `get_device_stats`（data 分类，📊）。
4. 文档 `docs/api/commands/data.md` 加章节 + `README.md` 索引。
5. 回执契约：一段文本聚合「电量 + 活跃时长 + 本 App 流量（累计/本月）+ 整机流量」，
   未授权项明确标注「未授予使用情况访问权」，不阻断其它项。

### 生效提示
| 任务 | 状态 |
|---|---|
| 设备统计命令 get_device_stats | ✅ 已实现 |

---

## ✅2026-11-05 · 无界面模式「首次配置破局 + PIN 自愈」（已对时，待落地）

> 用户拍板三点：① PIN **服务端权威**，客户端首次连上后**向服务端读取并本地缓存**，此后**只在本地判断**（离线可解锁）；
> ② 无界面模式开关**两处都放**（首次配置引导页 + 设置页）；③ 解锁入口 = **连续点 5 次锁图标**。

### 背景（调研结论，SSOT）
- 项目有**两套连接体系**：Bridge 主 WS（`DeviceStore`+`ServerAddress`，默认 `10.0.2.2:15160`，模拟器地址）
  与微信中心服务器 WS（`WechatPrefs.server_url`）。无界面模式控制的是 Bridge 主 WS 链路。
- 无界面模式当前由管理后台 `ui_lock_start/stop` 下发，状态存本地 Prefs（`UiLockController`），默认「未锁定」。
- **死循环根因**：若做「安装即默认无界面」，用户进不去设置页 → 填不了服务器 → 连不上后台 → 无法解锁 → 永久卡死。
- **二次死循环**：配置完成后，若服务器改地址/端口，客户端连不上 → 同样进不去界面改地址。

### 方案定稿（方案 A 引导页 + 方案 C PIN 自愈）

**① 首次配置引导页（破死循环）**
- 判断条件：`DeviceStore.serverHost()` 为 null（从未配过）或地址非法。
- 强制进入「初始化配置页」：填 Bridge 主服务器 host/port + 微信中心服务器地址/API Key。
- **测试连接成功才放行**（失败留在配置页，填错进不去）。
- 配置页**不受无界面模式拦截**（属「初始化」非「无界面」）。

**② 无界面模式客户端开关（两处）**
- 首次配置引导页（配完即见）+ 设置页，各加一个「无界面模式」手动开关。
- 开启即 `UiLockController.lock()`（全屏遮挡 + 页面资源释放，主 WS/保活/组网不动）。

**③ PIN 自愈（服务端权威 + 本地缓存 + 本地判断）**
- **权威**：PIN 存服务端（按 device_id），可在管理后台查看/重置。
- **首连读取**：客户端首次连接成功后，向服务端 `hello/register` 响应里读取 PIN，本地缓存到 Prefs。
- **本地判断**：此后解锁时**只读本地缓存的 PIN**，不依赖服务端在线（服务器改地址连不上时仍可离线解锁）。
- **解锁入口**：无界面遮挡层上「连续点 5 次锁图标」→ 弹 PIN 输入框 → 本地校验 → 正确则临时解锁进设置页改服务器地址。
- **重连同步**：改完地址重新连接成功后，重新从服务端拉取最新 PIN 覆盖本地缓存（若服务端有更新）。

### 待落地清单
1. **客户端「首次配置引导页」**：`FirstRunSetupScreen`（未配服务器时强制展示；测试连接成功才放行）。
2. **客户端 PIN 存储 + 校验**：`UiLockPin`（Prefs 缓存；`verify(pin)` 本地判断；`cacheFromServer(pin)` 首连写入）。
3. **客户端无界面遮挡层解锁入口**：`UiLockOverlay` 加「连续点 5 次锁图标」→ PIN 输入弹窗 → 校验成功临时解锁进设置页。
4. **客户端无界面开关（两处）**：引导页 + 设置页各加开关，调 `UiLockController.lock/unlock`。
5. **客户端首连拉 PIN**：`BridgeWebSocketClient` hello 响应解析 `ui_pin` 字段 → `UiLockPin.cacheFromServer`。
6. **服务端 PIN 存储**：`DeviceRegistry`/设备表加 `ui_pin` 字段；`register/hello` 响应携带 `ui_pin`；新增管理后台「查看/重置 PIN」。
7. **文档**：`docs/api/commands/system.md`（ui_lock 章节补 PIN 自愈）+ 新增「首次配置引导页」说明 + `README.md` 索引。

### 生效提示
| 任务 | 状态 |
|---|---|
| 无界面模式首次配置破局 + PIN 自愈 | ✅ 已实现 |
