# 极空保护板「靠近自动连接」需求与调研

> 状态：需求已确认（2026-11-08 10:46 用户拍板）；调研完成（2026-11-08 10:50，含代码盘点 + 外部调研），待用户审阅后进入实现。
> 关联代码：`app/.../jikong/JikongController.kt`、`JikongBleClient.kt`、`JikongPrefs.kt`、`app/.../ui/JikongPages.kt`、`core/keepalive/`
> 关联文档：`docs/dev/jikong-change-ledger.md`（改动台账）、`docs/keepalive/design.md`（保活设计）

---

## 一、需求最终版 V2（已确认）

### 1.1 总开关

- 设置页新增开关（如「靠近自动连接」），**默认关闭**。
- **关 = 完全维持现状**：必须进极空页面才触发连接/回连，行为一丝不变。
- **开 = 启用本需求描述的新模式**。

### 1.2 新模式行为（开关打开时）

**触发条件**
- 用户拿着手机靠近极空保护板 → 自动发起连接，**无需打开极空页面**。
- 连接目标：**仅默认设备**（未设置默认设备则此模式实际不生效，保持现状）。
- 覆盖 APP 全部状态：前台其他页面 / 后台运行 / **APP 被杀或重启后**（依赖开机自启 + 保活链路）。

**连接成功后（与现状完全一致，零改动）**
- 语音播报电量。
- 后台持续收数据、上报服务端、管理后台正常刷新。

**断开与重连语义**

| 断开原因 | 后续行为 |
|---|---|
| 走远导致信号丢失（自然断开） | **不抑制**——再次靠近时自动重连，重连成功后照常播报电量 |
| 用户在**极空页面**点击断开 | **抑制自动连接**，直到下次进极空页面或重启 APP 才恢复 |

**核心约束：省电第一，及时性可放宽**
- **电量消耗是唯一需要重点考虑的问题**。
- 及时性基准 30 秒内连上；若有必要（为显著省电）可以适当再放宽。
- 不在保护板附近时，后台探测活动必须做到尽可能省电。

### 1.3 一句话版本

> 设置里打开「靠近自动连接」后：手机靠近默认的极空保护板，30 秒左右（必要时可更慢）APP 自动连上并播报电量，无论 APP 在前台、后台还是被杀；走远自然断开不记仇，回来照常重连；只有用户在极空页面点断开会暂停此模式，直到下次进极空页或重启 APP；不打开开关则一切照旧。

### 1.4 已拍板的决策点（2026-11-08 AskUserQuestion）

| 决策点 | 拍板结果 |
|---|---|
| APP 进程不在运行时是否也要求自动连接 | **要**（被杀/重启后也要） |
| 手动断开后抑制持续到何时 | 直到下次进极空页面 / 重启 APP |
| 自动连接目标设备范围 | 仅默认设备 |
| 及时性体感标准 | 30 秒内可接受，必要时可再放宽（省电优先） |
| 功能入口 | 设置页开关，默认关闭 |

---

## 二、调研结论（2026-11-08）

### 2.1 现状代码盘点（可复用资产）

| 资产 | 现状 | 对本需求的可用性 |
|---|---|---|
| `KeepAliveService`（core-keepalive） | START_STICKY 常驻前台服务 + BootReceiver 开机自启，已在线上跑 | ✅ 承载「APP 被杀/重启后也要」的宿主，无需新建保活链路 |
| `autoReconnectEnabled` 状态机（JikongController v0.2.77~78） | connect 成功置 true；disconnect（手动）置 false；意外断开 180s 窗口重连（status=8 快档 800ms / 常规 3s） | ✅ 手动断开的抑制语义**天然已存在**，新模式可直接复用判定 |
| 默认设备 MAC（JikongPrefs.defaultMac） | 已持久化（`jikong_default_mac`） | ✅ 自动连接目标直接可用 |
| 语音播报链路（v0.2.88~100） | maybeAnnounce 每帧判定、firstAnnouncePending 进程首播、edge-tts 分段缓存 | ✅ 连接成功后的播报复用，零改动 |
| 后台数据接收/上报 | 断开仅清实时字段，连接期间持续 reportStatus | ✅ 现状已满足「后台不影响数据接收」 |
| BLE 权限 | BLUETOOTH_SCAN/CONNECT（12+ 运行时）、BLUETOOTH/ADMIN（≤30）、ACCESS_BACKGROUND_LOCATION 均已声明 | ✅ 权限基础齐备 |
| 12s 自愈重建（v0.2.80） | 连接 12s 无 0x02 → 主动断开+快档重建（限 3 次） | ✅ 兜底半死链路，新模式必须保留 |
| BLE 扫描（JikongBleClient.scan） | ScanFilter（service UUID 硬件过滤）+ SCAN_MODE_LOW_LATENCY | ⚠️ 仅适合前台交互扫描，不适合常驻后台（见 2.3 方案 C） |

**关键代码事实**：现有重连/自动连接全部 gate 在 `pageVisible || keepConnection`（JikongController.kt:242、601）——即当前架构下「不进极空页面就绝不连接」，这正是本需求要打破的点。

### 2.2 候选方案对比

| 维度 | 方案 A：GATT autoConnect 挂起 | 方案 B：PendingIntent 系统扫描 + 唤醒连接 | 方案 C：APP 层周期扫描 |
|---|---|---|---|
| 原理 | `connectGatt(autoConnect=true)` 挂给系统，蓝牙控制器后台监听广播，命中即建链 | `startScan(filter, settings, PendingIntent)` 挂给系统，扫到匹配设备唤醒 APP 再主动连 | 前台服务协程循环 扫N秒/歇M秒，扫到即连 |
| 功耗 | **最优**：控制器级 48ms 窗口/1280ms 周期（≈3.75% 占空比），零 CPU 参与 | 中：系统持续 duty-cycle 扫描（LOW_POWER 模式硬件 10% 占空比） | **最差**：每次扫描唤醒 CPU+BT 全速，且最易触发节流 |
| 建链延迟 | 取决于 BMS 广播间隔：短间隔（≤100ms）<2s；1s 间隔平均 ~10-30s（3 广播信道摊薄） | 扫描命中后走 direct connect，秒级 | 取决于扫描周期设计 |
| 系统限制 | **Android 历史上从未限制 autoConnect**（专家实证）；仅要求进程活着 | Android 8+ 可用；未过滤扫描息屏即停（带 filter 不受影响）；Android 9+ 30s 窗口内 >5 次 start/stop 静默封锁 | 同左，且进程死即全部失效 |
| 进程被杀后 | 失效（需 keepalive 承载） | **仍有效**（系统持有 PendingIntent，可反向拉起进程） | 失效 |
| 走远断开语义 | **天然满足**：出范围断开后系统自动持续尝试，回来即连 | 需自己实现（断开后重挂扫描） | 需自己实现重扫循环 |
| 手动断开语义 | **天然满足**：disconnect + close GATT 即彻底停止系统后台连接 | 需手动摘除 PendingIntent | 停循环即停 |
| 行业实测参考 | autoConnect 成功率 99.2%、断连恢复 ~1.8s（对比 direct connect 68% / 12.3s，健身房多设备环境） | — | — |

### 2.3 推荐方案：A 为主（autoConnect 挂起），B 的 PendingIntent 作为被杀兜底（二期保留）

**核心架构**：
```
设置开关「靠近自动连接」打开 + 已设默认设备
  → KeepAliveService 承载，对默认设备 MAC 挂 connectGatt(autoConnect=true)
  → 用户靠近 → BMS 广播被控制器监听命中 → 系统自动建链
  → onConnectionStateChange(STATE_CONNECTED) → 走现有 connect 成功路径
     （startPolling → 0x96 ramp → 0x02 数据流 → maybeAnnounce 播报）
  → 用户走远 → status=8 断开 → 系统 autoConnect 自动持续重试（零 APP 逻辑）
  → 用户回来自动重连成功 → 同上播报
  → 用户在极空页点断开 → disconnect + close → 系统停止后台连接（抑制达成）
     → 重进极空页或重启 APP → 重新挂起 autoConnect（抑制解除）
```

**理由**：
1. 省电是用户唯一核心关切——autoConnect 是三条路线中唯一零 CPU、控制器级占空比监听的方案，且 Android 从未对其施加后台限制。
2. 「走远断开不抑制 / 手动断开抑制」两个语义 autoConnect 天然自带，APP 层几乎零状态机新增。
3. 30s 延迟容忍度与 autoConnect 的延迟特性（广播间隔依赖）匹配良好；BMS 连接态广播间隔通常 ≤1s，多数场景实际 10s 内连上。
4. 可选增强（二期保留方案，详述见 §2.9）：再挂一个 PendingIntent 扫描（filter 默认设备 MAC），在 KeepAliveService 也被系统杀死时仍能拉起进程重挂 autoConnect——把「必可达」做到极致。

> **用户拍板（2026-11-08 11:03）：一期只做方案 A；方案 B 的协同方案完整保留在本文档（§2.9），二期按真机进程被杀率决定是否实施。**

### 2.4 架构承载与权限（Android 14+ 硬要求）

- **targetSdk=36**：Android 14+ 起做 BLE 长连接的前台服务**必须**声明 `foregroundServiceType="connectedDevice"` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` 权限，且需持有 BLUETOOTH_CONNECT/SCAN 运行时权限（已有），否则服务做 BLE 工作时会被系统杀。
- 现有 `KeepAliveService` 的 `foregroundServiceType="mediaPlayback"` **与 BLE 工作不匹配**，需二选一：
  - 改为 `mediaPlayback|connectedDevice` 双类型（改动最小，startForeground 时传对应位掩码）；
  - 或新建 `JikongProximityService`（connectedDevice 类型），由设置开关控制启停——与项目「保活与业务解耦」设计原则（KeepAliveService 头注释）更一致，**倾向此方案**。
- Manifest 需新增：`FOREGROUND_SERVICE_CONNECTED_DEVICE` 权限（其余权限已齐）。
- autoConnect 模式建链后建议立即 `requestConnectionPriority(HIGH)` 拉连接间隔（官方时序已有同类实践，v0.2.82 曾验证过 API 可用性）。

### 2.5 状态机扩展设计要点

新增持久化状态（JikongPrefs）：`proximityAutoConnect`（设置开关，默认 false）。

| 事件 | 新模式（开关开）下的行为 |
|---|---|
| 开关打开 | 若有默认设备 → 挂 autoConnect（经前台服务） |
| 开关关闭 | 摘除 autoConnect，回到现状行为 |
| autoConnect 建链成功 | 复用现有 connect 成功路径：startPolling、清演示、播报链路全复用 |
| 信号断开（status=8 等） | **不启动** APP 层 180s 重连循环（系统 autoConnect 自己在试）；12s 自愈重建保留作半死链路兜底 |
| 用户在极空页点断开 | 现有 disconnect() 基础上确保 **close GATT**（仅 disconnect 不够，autoConnect 会复活）+ 置抑制标记，停止重挂 |
| 用户重进极空页 / 重启 APP | 清抑制标记 → 重挂 autoConnect |
| 用户手动在扫描页连了别的设备 | 摘除 autoConnect（避免双连接竞争，复用 v0.2.93 抢占式连接治理思路） |

**与现有重连状态机的分工**：现状 180s 窗口循环服务于「页面在前台时的意外断开快速恢复」；新模式下走远断开交由系统 autoConnect（无窗口限制、持续尝试）。两套机制按 `proximityMode` 分流，互不干扰。

### 2.6 功耗评估（量化数据见 §2.10）

- **挂起监听阶段（不在保护板附近）**：BT 控制器 48ms/1280ms 占空比监听，无 CPU、无唤醒、无网络交互（**零流量**：autoConnect 挂起是纯蓝牙控制器行为，连 APP 进程都不参与；连上后的数据上报走既有 WS 心跳链路，同样无新增流量）。
- **连接保持阶段（在附近）**：与现状「手动连上后保持」完全相同，无新增。
- **结论**：方案 A 挂起阶段耗电每天 <0.5%（详细估算与实测方法见 §2.10），低于「蓝牙开关本身」的底噪（1~2%/天），用户不可感知。

### 2.7 风险与待真机验证清单

1. **BMS 休眠半死链路**（v0.2.80~82 已实证：闲置后首链路扫得到连得上但不出数）：autoConnect 首建链大概率遇到。依赖现有 12s 自愈重建兜底；自愈重建是 direct connect 语义，需验证「autoConnect 首发 → 自愈 direct 重建」组合链路的实际行为。
2. **autoConnect 建链后连接参数偏保守**（系统为省电倾向长连接间隔）→ 0x02 数据流 300B 大帧可能传不动 → 建链后立即 requestConnectionPriority(HIGH) 缓解，需实测。
3. **BMS 广播间隔未知**：若待机广播间隔 >1s，autoConnect 命中延迟可能超 30s → 真机抓广播间隔（可用 nRF Connect 观察）校准预期；必要时叠加方案 B 增强。
4. **部分 ROM 蓝牙栈对 autoConnect 兼容性**（断开后不重试、蓝牙开关切换后挂起丢失）→ 需监听 ACTION_STATE_CHANGED 重挂（业界标准做法）+ 真机回归。
5. **双连接竞争**：用户手动扫描连接时 autoConnect 仍在后台 → 必须先摘除（v0.2.93 已有抢占治理范式）。
6. **Android 15+ 对 BOOT_COMPLETED 启动前台服务的新限制**：BootReceiver 现有实现在跑，connectedDevice 类型属于允许清单，风险低但需回归真机重启场景。

### 2.8 开放问题（已拍板）

**后台自动连上时，语音播报是否要播？** → **用户拍板（2026-11-08 10:55）：① 后台也播**。
- 实现含义：新模式下 `maybeAnnounce` 的 `pageVisible` gate 需为靠近自动连接场景放开（或加独立判定），手机在口袋里连上也播报电量；现状「非靠近模式的普通后台断连重连」是否同样放开，实现时再定（倾向保持现状，仅靠近建链场景播）。

### 2.9 方案 B 详述与 A+B 协同（二期保留，一期不实施）

**机制原理（2026-11-08 向用户讲解定稿）**：

- **方案 A 的唯一死穴**：GATT 对象活在 APP 进程里，进程被杀（系统极端内存回收、厂商 ROM 猎杀、用户强制停止）= autoConnect 挂起蒸发、控制器停止监听。KeepAliveService（START_STICKY + 开机自启）让进程极难被杀，但不是 100%。
- **方案 B 的定位**：保险丝，自己**从不建链**，只负责在 A 死掉后把它重新点火。

**协同架构（三层）**：

| 层 | 承载内容 | 说明 |
|---|---|---|
| Bridge APP 进程 | ① 新极空前台服务（connectedDevice 类型，承载挂起）② autoConnect 挂起（GATT 对象）③ 唤醒接收器（BroadcastReceiver，静态注册） | 进程活着，A 才有效 |
| Android 系统蓝牙服务（独立系统进程） | PendingIntent 扫描注册表（filter=默认设备 MAC） | **Bridge 进程死了它照样替你扫**（系统级 LOW_POWER 节奏，硬件 10% 占空比） |
| 蓝牙控制器（独立硬件子系统） | 48ms/1280ms 占空比监听、命中广播直接建链、走远断开后自动持续重试 | 「走远断开不抑制」语义的出处 |

**两条路径**：

1. **日常路径（99% 的时间）**：APP 进程活着 → 挂 autoConnect → 一切交给控制器（监听/命中/建链/走远重试，全系统行为，APP 零代码零功耗）→ 连上后回调进现有代码路径（startPolling → 数据流 → 播报）。
2. **保险路径（仅进程死后触发一次）**：APP 进程被杀 → A 蒸发，但 B 的 PendingIntent 扫描仍在系统侧 → 手机靠近 BMS、系统扫到目标 MAC → 投递 PendingIntent → **把 Bridge 进程从死里拉活** → 接收器醒来检查：A 挂着没？没挂 → 重挂 → 回到日常路径。若 A 本来就活着，接收器直接退出（幂等，无副作用）。

**实施门槛（二期时评估）**：
- `startScan(filters, settings, PendingIntent)` 为 Android 8.0+ API（minSdk 24，需版本分支，8.0 以下无此能力）。
- 接收器静态注册 Manifest；PendingIntent 扫描命中的广播投递属于系统豁免场景，可唤醒静态接收器。
- 常驻代价：系统侧 10% 占空比扫描（约每天 3% 电池寿命量级，UNC CACM2021 数据），明显高于 A 的挂起监听——**这正是二期才做的原因**。
- 触发条件（何时值得上）：真机运行一段时间后，日志显示进程被杀导致模式失效的情况可忽略 → B 永远不上；确有发生 → 补 B。

### 2.10 方案 A 耗电量量化与统计方法（2026-11-08 调研）

#### 2.10.1 流量结论：零新增

- 挂起监听阶段：纯蓝牙控制器行为，无任何网络交互，**零流量**。
- 建链/数据阶段：BMS ↔ 手机走蓝牙（不耗蜂窝/Wi-Fi）；数据上报走既有 WS 心跳链路（本来就在跑，无新增流量）。

#### 2.10.2 耗电量估算（文献数据推导）

数据源：UNC CACM2021（BLE 扫描能耗建模，BLE112/nRF52832 双芯片基准）、webidroid（BLE 连接待机功耗）、HKUST（Android 连续 BLE 扫描 200mW+ 实测）、lifetips（现代 SoC BT 待机 0.5~1.2mW）。

| 阶段 | 工作机制 | 平均功耗估算 | 每天耗电（≈19Wh 电池） |
|---|---|---|---|
| **挂起监听（不在附近，本方案新增）** | 3.75% 占空比 × 射频工作态 30~50mW | 1.1~1.9 mW | **0.14%~0.24%** |
| 命中建链瞬间 | 单次几百 ms @ 50~100mW | 事件型 | 忽略不计 |
| 连接保持（在附近） | BLE 待机连接 0.1~2mW + 0x02 数据流 | 2~10 mW | 0.25%~1.3%（**与现状相同，非新增**） |

**交叉验证**（UNC CACM2021 模型）：SCAN_MODE_LOW_POWER（10% 占空比扫描）约消耗 3% 电池寿命；GAEN（Google 曝光通知，系统级低占空比）<0.4%；现代 BT SoC 比 BLE112 基准低约 75%。autoConnect 的 3.75% 占空比 + 控制器执行（无 CPU 唤醒开销）落在两者之间，**每天 0.3%~0.5% 封顶，实际大概率 <0.3%**。

**对照参考（否决方案 C 的定量依据）**：
- 方案 C（APP 层周期扫描，如每 30s 扫 5s ≈ 16.7% 占空比 + CPU 唤醒）：HKUST 实测连续扫描 200mW+，折算每天 **4%~7%**——不可接受。
- 前台扫描页 LOW_LATENCY（≈100% 占空比）：UNC 数据约 **20%** 电池寿命缩减——仅适合用户正盯着的短暂场景（现状如此使用，无问题）。
- 蓝牙开关本身的底噪（系统自身 Find My Device 等扫描）：1~2%/天——**本方案挂起耗电低于这个底噪**。

#### 2.10.3 统计/实测方法（发版后验证用）

| 方法 | 操作 | 适用 |
|---|---|---|
| **24h 对照实验（推荐主口径）** | 同一部手机：模式关跑 24h 记掉电%，模式开（人在 BMS 附近与远离时段都覆盖）跑 24h 记掉电%，差值即增量。控制变量：同时段、相似 Wi-Fi/信号环境、相近屏幕使用量 | 用户可直接感知验证 |
| **adb batterystats（归因验证）** | `adb shell dumpsys batterystats --reset` → 跑固定时长 → `adb shell dumpsys batterystats --charged my.bridge.com`，看 Bluetooth 段的 BLE scan 时间与耗电归因 | 精确定位耗电归因（项目 tools/adb 可用） |
| dumpsys bluetooth_manager | `adb shell dumpsys bluetooth_manager` 看后台扫描/连接状态是否符合预期（无意外扫描、挂起正常） | 链路健康检查 |
| Battery Historian | bugreport 导出可视化，BLE scan 与 wake 粒度最细 | 可选；需安装工具（**遵守项目约定：安装前须经用户同意**） |
| 电池电流直读 | `cat /sys/class/power_supply/battery/current_now` 前后对比 | 机型相关，辅助手段 |
| 硬件功耗仪（Monsoon 等） | 拆机供电实测 | 最准但不现实，不采用 |

推荐组合：**24h 对照实验（宏观结论）+ batterystats（归因佐证）**，与项目流量测量（docs/dev/traffic-measurement.md）同风格的实证口径。

---

## 三、实现方案设计（2026-11-08，待用户审阅后动码）

### 3.0 设计总览

```
设置开关（默认关）──┐
默认设备 MAC ──────┼─→ JikongProximityService（新前台服务，connectedDevice 类型）
手动断开抑制标志 ──┘         │ 挂起/摘除/重挂 connectGatt(autoConnect=true)
                             ▼
                    JikongBleClient（复用现有 gattCallback 全链路：
                    MTU→发现服务→CCCD→setConnected→onConnectionChanged）
                             ▼
                    JikongController（proximity 状态机分流：
                    建链成功初始化 / 走远断开不重连 / 手动断开抑制 / 后台播报）
```

核心原则：**所有初始化/数据流/播报复用现有链路，新模式只做「分流判定」**，不复制任何连接逻辑。

### 3.1 改动清单（文件级）

| # | 文件 | 改动 | 类型 |
|---|---|---|---|
| 1 | `jikong/JikongPrefs.kt` | +`KEY_PROXIMITY_AUTO_CONNECT`（默认 false） | 新增设置项 |
| 2 | `jikong/JikongProximityService.kt` | **新文件**：connectedDevice 前台服务 + 事件驱动 evaluate | 新建 |
| 3 | `jikong/JikongBleClient.kt` | +`attachAutoConnect()` / +`detachAutoConnect()` / +`autoConnectAttached` 状态 | 扩展 |
| 4 | `jikong/JikongController.kt` | proximity 状态机分流（4 个触点，见 3.4） | 扩展 |
| 5 | `ui/JikongPages.kt` | SettingsView +开关行；setPageVisible 联动抑制解除 | UI |
| 6 | `app/src/main/AndroidManifest.xml` | +`FOREGROUND_SERVICE_CONNECTED_DEVICE` 权限 + service 声明 | 配置 |
| 7 | `BridgeApplication.kt` | onCreate 检查开关 → 启动服务（覆盖开机/进程重建链路） | 挂载 |
| 8 | `jikong/JikongController.kt` buildReportStatus | app 块 +`proximity_auto_connect`/`proximity_suppressed` 字段 | 上报 |
| 9 | `admin` + 服务端 `jikong.py` | 后台镜像开关（`jikong_set_proximity`，v0.2.108 实施，需服务端重启） | 新增 |
| 10 | `Command.kt` + `di/CommandModule.kt` | +`JIKONG_SET_PROXIMITY` 命令分发 + `executeJikongSetProximity` | 新增 |
| 11 | `jikong/JikongController.kt` | +`setProximityAutoConnect()`（写 Prefs+evaluate/stop+forceReport，与手机设置页同语义） | 新增 |

### 3.2 JikongProximityService（新文件，范式照 SshServerService）

- **Manifest 四件套**：`foregroundServiceType="connectedDevice"` + `FOREGROUND_SERVICE_CONNECTED_DEVICE` 权限 + startForeground 传 `FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE`（API 29+ 分支）+ `BLUETOOTH_CONNECT` 运行时权限前提（已有）。
- **START_STICKY**：进程被杀系统重建时 `onStartCommand(null)` → 按当前 Prefs 重新 evaluate（自然自愈）。
- **通知**：独立 ID 2104、渠道 `jikong_proximity_channel`（IMPORTANCE_LOW，照 keep_alive_channel_v2 经验）；通知文案反映状态：「极空监听中（默认设备）」/「极空已连接」。
- **Action**：
  - `ACTION_REFRESH`（startService 语义，幂等）：核心入口 `evaluate(context)`；
  - `ACTION_STOP`：摘除挂起 + stopSelf（开关关闭时）。
- **evaluate() 判定（全部条件同时满足才挂起）**：
  1. `JikongPrefs.proximityAutoConnect` 开关开；
  2. 默认设备 MAC 非空；
  3. `manualSuppressed == false`（controller 内存标志）；
  4. `!JikongBleClient.connected`（已连接无需挂）；
  5. `!JikongBleClient.autoConnectAttached`（幂等防重复）；
  6. `!demoMode`（演示期间不挂，见 3.8-⑤）。
  满足 → `attachAutoConnect(ctx, defaultMac)`；否则若已挂且条件失效（开关关/抑制/演示）→ `detachAutoConnect()`。
- **蓝牙状态守护**：服务内动态注册 `BluetoothAdapter.ACTION_STATE_CHANGED` receiver，蓝牙重启完成（STATE_ON）后 evaluate 重挂（§2.7-4 风险消解）。
- **常驻代价**：服务本身无循环、无协程——纯粹持有通知 + 挂起状态，耗电即 §2.10 的控制器监听（<0.5%/天）。

### 3.3 JikongBleClient 扩展

```kotlin
/** autoConnect 挂起中（挂起≠连接：GATT 对象在、链路未建立）。 */
@Volatile
var autoConnectAttached = false
    private set

/** 挂起 autoConnect：不挂 waiter、无超时（可能几十秒后才建链，等控制器回调即可）。 */
fun attachAutoConnect(context: Context, address: String): Boolean {
    if (connected || autoConnectAttached) return autoConnectAttached
    val a = ensureAdapter(context) ?: return false
    val dev = a.getRemoteDevice(address) ?: return false
    connectedDeviceAddress = address
    connectedDeviceName = runCatching { dev.name }.getOrNull() ?: address
    val g = dev.connectGatt(context, true, gattCallback, BluetoothDevice.TRANSPORT_LE)
    gatt = g
    autoConnectAttached = true
    return true
}

/** 摘除挂起：disconnect + close（仅 disconnect 会被系统自动重连复活，必须 close）。 */
fun detachAutoConnect() {
    autoConnectAttached = false
    if (!connected) {           // 已连接时走正常 disconnect() 语义，勿动链路
        runCatching { gatt?.disconnect() }
        runCatching { gatt?.close() }
        gatt = null
    }
}
```

- **复用 gattCallback 全链路**：autoConnect 建链成功同样走 `STATE_CONNECTED → requestMtu(256) → onMtuChanged → discoverServices → onServicesDiscovered → selectCharacters → setConnected(true)`；`connectWaiter` 为 null 时所有 `connectWaiter?.let` 天然安全（现有代码已是 null 安全）。
- **走远断开**：`STATE_DISCONNECTED → setConnected(false)`，gatt 不 close → 系统自动持续重试（这正是需求要的语义，零代码）。
- **与手动 connect 的互斥**：现有 `connect()` 开头就 `disconnect()`（close GATT + 清 autoConnectAttached），天然抢占；`detachAutoConnect()` 在已连接时不动链路，避免误伤。

### 3.4 JikongController 状态机分流（4 个触点）

新增内存标志（**不持久化**——进程重启自然复位，恰好实现「重启 APP 解除抑制」语义）：

```kotlin
/** 用户在极空页手动断开的抑制标志（重启进程/重进极空页解除）。 */
@Volatile
var manualSuppressed = false
    private set

/** 靠近建链成功待播报标志（maybeAnnounce 强制播一次，受小喇叭静音拦截，播出才消耗）。 */
@Volatile
private var proximityAnnouncePending = false

/** 新模式是否生效（开关开 + 已设默认设备）。 */
val proximityModeActive: Boolean
    get() = appContext?.let {
        JikongPrefs.proximityAutoConnect(it) && JikongPrefs.defaultMac(it).isNotBlank()
    } == true
```

**触点 ① onConnectionChanged(connected=true)（init 回调）**——识别「非手动、非重连」的建链即 proximity 建链：

```kotlin
if (connected && !connectInFlight && reconnectJob?.isActive != true && proximityModeActive) {
    // 靠近自动建链成功 → 执行与 connect()/startReconnect() 相同的成功初始化
    if (demoMode) { SpeedDash.clearDemoInjection(); demoOdoRecords.clear() }
    demoMode = false
    prevAnnounceSoc = -1
    clearScanCache()
    startPolling()
    proximityAnnouncePending = true   // §2.8 拍板：后台也播（下一帧强制播）
    Timber.tag(TAG).i("靠近自动连接成功（autoConnect 建链）")
}
```

**触点 ② onConnectionChanged(connected=false)**——走远断开不启动 APP 层重连：

```kotlin
// 原：if (autoReconnectEnabled && (pageVisible || keepConnection)) startReconnect(...)
// 改：if (autoReconnectEnabled && (pageVisible || keepConnection)
//        && !proximityModeActive) startReconnect(fast = dataRecyclePending)
//     若 proximityModeActive：什么都不做——autoConnect GATT 未 close，系统自己在持续重试
```

**触点 ③ disconnect()（用户手动断开）**——置抑制 + 摘除挂起：

```kotlin
fun disconnect() {
    autoReconnectEnabled = false
    if (proximityModeActive) {
        manualSuppressed = true              // 抑制：不再自动挂起
        JikongBleClient.detachAutoConnect()  // 已连接态：正常 disconnect() 走完（close GATT）
        JikongProximityService.stop(appContext)  // 服务内 evaluate 不会重新挂
    }
    ...（现有逻辑不动）
}
```

> 关键：现有 `JikongBleClient.disconnect()` 已是 `disconnect()+close()`——close 即彻底终止系统后台重连，**抑制语义零额外实现**，只需防 evaluate 重挂。

**触点 ④ setPageVisible(visible)**——抑制解除 + 连接保持：

```kotlin
fun setPageVisible(visible: Boolean) {
    pageVisible = visible
    if (visible) {
        // 重进极空页 = 解除手动断开抑制（拍板语义），重评估挂起
        if (manualSuppressed) {
            manualSuppressed = false
            JikongProximityService.evaluate(appContext)
        }
        if (JikongBleClient.connected) startPolling()
    } else {
        if (proximityModeActive) {
            // 新模式：页面切走不断开、不停守护（后台数据接收是需求；轮询循环
            // 仅 400ms delay + 偶发写命令，代价可忽略）
            // —— 等同 keepConnection=true 分支但保留 polling
        } else if (!keepConnection) {
            ...（现状逻辑不动：断开释放省电）
        } else {
            stopPolling()
        }
    }
}
```

**12s 自愈重建与 autoConnect 的组合**（§2.7-1 风险消解）：startPolling 的 `datalessRecycles` 路径调用 `JikongBleClient.disconnect()` 时，autoConnect GATT 上 disconnect 不 close → **系统立即自动重建链路**（等同原快档重连，且更快），重建成功经触点 ① 重新初始化。`dataRecyclePending` 标志在 proximity 模式下无需使用（不走 startReconnect）。**此组合链路是真机验证重点。**

> **⚠ 真机验证推翻上述假设（v0.2.109 修复，2026-11-08 12:18~12:23 设备 f2c38ebee251a196 日志实证）**：
> 「重挂 autoConnect 让系统自动重建」**唤醒不了半死链的休眠 BMS 模块**——实测反复「挂起→建链→只回 `AT\r\n`/0x01→12s 断开→再挂起」死循环，连续 3 次重建 + 3 次后「继续 ramp」仍无 0x02，永不播报。半死链的唤醒**必须靠直连重建**（connectGatt autoConnect=false，v0.2.80 手动连接实证第 2 条直连链路立即有数据）。
> **修正**：触点②断开分流在 `dataRecyclePending=true`（12s 无 0x02 主动重建）时改走 `startProximityReconnect()`（对默认设备立即直连重建），而非 evaluateInPlace 重挂起；`false`（走远自然断开）仍保持重挂起。详见台账 v0.2.109 条目。

### 3.5 播报 gate 放开（§2.8 拍板实现）

`maybeAnnounce()` 两处改动：

```kotlin
// ① gate 放开：新模式下页面不可见也判定（普通模式保持现状）
// 原：if (demoMode || !pageVisible) return
// 改：
if (demoMode || (!pageVisible && !proximityModeActive)) return

// ② 靠近建链强制播报（第一帧播一次；受小喇叭静音拦截——尊重用户主动静音，
//    区别于 firstAnnouncePending 的绕静音语义）
val forcedProximity = proximityAnnouncePending
if (!(forced || forcedProximity || changed || due || warnDue)) { ... return }
proximityAnnouncePending = false
```

- 播出内容与现状完全一致（「当前电量 N%」+低电追加），`speakEnabled` 总开关照常生效（关播报则什么都不播，合理）。

### 3.6 设置页 UI + 上报

- **SettingsView 新增开关行**（照 v0.2.83 dashEnabled 范式，1800 行 Switch 样式）：「靠近自动连接」+ 说明文案「靠近默认保护板自动连接并播报电量；在极空页断开后暂停，直至重新进入页面」。
- 切换时：写 `JikongPrefs.setProximityAutoConnect` → `JikongProximityService.evaluate/stop` → **forceReport()（铁律：Prefs 变更必须手动上报）**。
- 无默认设备时点开：回弹开关 + Toast「请先连接设备并设为默认」。
- **上报扩展**：buildReportStatus 的 app 块 + `proximity_auto_connect: Boolean`、`proximity_suppressed: Boolean`（管理后台可见模式状态；admin 端字段与显示镜像已于 v0.2.108 实施——后台 Tab3「靠近自动连接」卡可编辑开关 + 抑制态只读提示，服务端 `jikong_set_proximity` 命令透传，需重启服务端）。

### 3.7 Manifest / 挂载

```xml
<uses-permission android:name="android.permission.FOREGROUND_SERVICE_CONNECTED_DEVICE" />

<service
    android:name="my.bridge.com.jikong.JikongProximityService"
    android:exported="false"
    android:foregroundServiceType="connectedDevice" />
```

- **BridgeApplication.onCreate**：`if (JikongPrefs.proximityAutoConnect(this)) JikongProximityService.evaluate(this)` —— 覆盖三条链路：用户开开关（即时）、进程被杀重建（Application 重建）、开机（BootReceiver 拉起 KeepAliveService → 进程创建 → Application.onCreate）。

### 3.8 与现有机制的冲突消解清单

| # | 现有机制 | 冲突点 | 消解 |
|---|---|---|---|
| ① | setPageVisible(false) 主动断开释放（省电） | 新模式要求后台保持 | 触点 ④ 分流：proximityModeActive 不断开（见 3.4） |
| ② | startReconnect 180s 窗口循环 | 与系统 autoConnect 重试叠加 | 触点 ② 分流：proximity 模式不走 APP 层重连 |
| ③ | 手动 connect()（扫描页点连接） | autoConnect 在后台可能同时建链 | connect() 开头 disconnect() 已 close autoConnect GATT（天然抢占）；controller.connect 入口 + `JikongBleClient.autoConnectAttached=false`，完成后 evaluate 重新挂起 |
| ④ | enterDemoMode 掐在飞连接 | autoConnect 建链会把用户踢出演示 | evaluate 条件 ⑥：demoMode 时不挂起；enterDemoMode 显式 detach |
| ⑤ | firstAnnouncePending 绕静音首播 | 与 proximityAnnouncePending 并存 | 两者独立消耗，语义互不干扰（前者=进程首播绕静音；后者=靠近建链播、受静音） |
| ⑥ | keepConnection 手动保留开关 | 与 proximity 保持语义并存 | proximityModeActive 优先级更高（覆盖）；普通模式照旧 |
| ⑦ | autoReconnectEnabled 状态机 | proximity 建链是否置位 | **不置位**（该标志属 APP 层重连循环；proximity 断开分流已绕开它，置位反而引入 180s 窗口语义混乱） |

### 3.9 版本 / 发布 / 留档计划

- 版本：**v0.2.107**（叠加在 v0.2.106 基线上）。
- **改前留档（铁律）**：动码前在 `docs/dev/jikong-change-ledger.md` §一 登记本条目，git 快照提交作回退锚点；改动文件为 3.1 清单 #1~#8（#9 可选不做）。回滚配方：`git checkout <锚点> -- <改动文件> && rm app/src/main/java/my/bridge/com/jikong/JikongProximityService.kt`。
- 发布：release.py 走 tag 触发 CI（或网络抖动时 api_push.py 通道）；服务端零改动（可选 #9 不做时），无需重启。
- 真机未连 adb 时走 check_update 升级验证。

### 3.10 真机验证清单

1. **基本链路**：开开关 → 息屏放兜里靠近保护板 → ≤30s 自动连上（看日志「靠近自动连接成功」）→ 口袋里听到电量播报。
2. **走远回来**：连着走远自然断开 → 回来 → 自动重连 + 再播报；反复多次验证不失效。
3. **手动断开抑制**：进极空页点断开 → 手机放保护板上也不自动连；重进极空页 → 恢复自动连。
4. **重启恢复**：重启 APP / 重启手机 → 开关保持开 → 靠近仍能自动连。
5. **半死链路组合**：BMS 闲置 ≥10min 后靠近 → 观察 autoConnect 首建链是否触发 12s 自愈重建 → 重建后出数据。
6. **后台数据**：连接期间切后台 → 管理后台极空页数据持续刷新（jikong_state 上报不断流）。
7. **双连接竞争**：开着开关，进扫描页手动连另一台设备 → 不冲突、连得上；断开后回到默认设备挂起。
8. **演示隔离**：开开关 + 进演示模式 → 不发生真机连接踢演示；退演示后恢复挂起。
9. **耗电实测**：§2.10.3 的 24h 对照实验 + batterystats 归因，确认 <1%/天。
10. **蓝牙开关**：连接/挂起状态下关开手机蓝牙 → 自动恢复挂起。


## 四、握手剖析（2026-11-08 深挖：为何「靠近能立马发现，但多轮 12s 超时才收到数据」）

### 4.1 现象与证据（用户 2026-11-08 13:49 真机反馈，v0.2.109）

- 靠近后 autoConnect 能**立马发现并建链**（发现速度正常），但**收到数据（0x02）要 3~4 轮 12s 超时直连重建**，总耗时近 1 分钟才播报电量。
- 期间保护板发出「滴滴滴」蜂鸣（BMS 每次被唤醒都会叫），用户据此判断「已与保护板交互重连成功」，但数据迟迟不来。
- 用户关键对比：**官方 APP 一次交互即成功**（无三四轮重连）。

### 4.2 协议本身正确（JK02，无需重写）

当前握手走的 JK02 二进制帧协议，已被开源社区（BHP1000/JK-BMS-BLE-ESP32、syssi/esphome-jk-bms、peff74/Arduino-jk-bms）实证正确：

- 命令帧：`AA 55 90 EB | cmd 1B | len 1B | value 4B LE | zero 9B | CRC 1B`（20 字节）
- 响应帧：`55 AA EB 90 | type 1B | ... | CRC`（300 字节）
- 连接序列：连接 → 订阅 0xFFE1 通知 → 发 0x97（设备信息）→ 等 500ms → 发 0x96（读单体）→ BMS 回 0x01+0x03+0x02 爆发 → 持续 0x02 流

手动直连（BMS 已醒）时我们第 1 条链路就出数据，证明协议与字段偏移全对。**问题不在协议，而在「半死链唤醒」**。

### 4.3 半死链 = BMS 硬件休眠现象（社区证实官方也偶发）

- BMS 蓝牙模块（CC2541/BT05 透传桥）在 MCU 休眠时**广播仍发、连接能建**，但 MCU 不转发 0x97/0x96 命令（只回 `AT\r\n` 与偶发 0x01）——这就是「半死链」。
- **社区实证官方也有此问题**：DIY Solar Forum 帖子（2025-12）明确「关开手机蓝牙再连，有时要 2~3 次才成功」「必须实际靠近、丢包多了 BMS 就不懂通信了」。
- 但官方同场景通常是**单条链路内等到数据**（十几秒链路不断），而我们曾 13s 必 status=8 断开、现在 12s 主动断开重建——**这是官方与我们的关键行为差异**。

### 4.4 官方 vs 我们的三处具体差异（按嫌疑排序）

| # | 差异点 | 官方 APP | 我们当前（v0.2.109） | 台账依据 |
|---|---|---|---|---|
| 1 | **连接优先级** | STATE_CONNECTED 后立即 `requestConnectionPriority(HIGH)`（7.5ms 连接间隔） | **完全没有**（代码库 grep 无此调用，走系统默认 30~50ms） | v0.2.82 dexdump 逆向实证 |
| 2 | **触发命令策略** | 0x96 **只发一次后等待**（CONNECT_TIMEOUT 10s 报错不重连） | 启动 ramp **每 1.2s 补发 0x96 + 12s 强制断开重建**（「0x96 轰炸」） | 台账遗留疑点 650 行「去掉启动 ramp 的 0x96 轰炸，官方只发一次后等待」 |
| 3 | 第 3 条命令 | 0x6C 时间同步（第 3 声蜂鸣） | 无 | v0.2.84 二轮逆向：**0x6C 是「收到数据后才发」**，非唤醒帧，嫌疑最低 |

### 4.5 根因假设与推荐实验（单变量，一次只测一个）

**主假设**：官方一次成功的关键不是「多了某条神秘激活帧」，而是**「高频连接事件（HIGH 优先级）+ 只发一次 0x96 后耐心等」**——高频连接事件持续唤醒 MCU、单次触发不打断其苏醒时序；而我们的「默认优先级 + 每 1.2s 轰炸 + 12s 强制断开」反而**在 MCU 即将苏醒时断开重建、打断唤醒**，于是需要 3~4 轮。

**推荐实验顺序（严格单变量，改前必留档）**：

1. **实验①（最优先）**：连接建立后立即 `g.requestConnectionPriority(CONNECTION_PRIORITY_HIGH)`（对比官方时序），其余不动。观察首建链是否一次出数据。
2. **实验②**：把启动 ramp 的 0x96 轰炸去掉——0x96 只发一次（或降为 4s 一次），把 12s 强制重建阈值放宽（如 20~30s）或直接移除（回到「耐心等」语义）。观察是否单链路等到数据。
3. **实验③（低优先）**：复现官方第 3 命令 0x6C 时间同步（在 0x02 到达后发，语义=同步时间/日志），仅作保真对照，预期与唤醒无关。

> 备注：台账 v0.2.84 遗留的另一个嫌疑「快档重连 800ms→2s（给 BMS 清链时间）」也值得并入实验②一起验证（当前 proximity 直连重建后若失败立即重挂，等效于高频重连）。

### 4.6 结论与待办

- **当前功能已完全跑通**（靠近自动连 + 播报 + 后台适配全链路），体验差仅在于「半死链唤醒多轮」。恢复锚点 `0f16419`（v0.2.109）。
- **下一步不是改协议，是复刻官方的「连接优先级 + 触发策略」**，单变量真机实验定位。此剖析留档，待真机实验后回填结论。


