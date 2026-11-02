# 心跳与周期任务台账

> **权威文档**：本项目所有「心跳 / 保活 / 周期任务」的唯一台账。
> 调整任何周期前先查本文档；调完必须回来更新本表。
> 背景：v0.2.20 微信集成引入第 5 套长连接、v0.2.30 VNT 组网引入第 6 套（Rust 内部黑盒保活），
> 周期参数散落各处，本文档用于避免「改一处、忘四处」。VNT 的周期不可从 Java 侧调整，见 §1.2。

---

## 一、全景清单

### 1.1 进程级常驻（App 启动即连，永不主动断）

| 连接          | 源码                                                                      | 应用层心跳   | 传输层 ping             | Pong/判死 | 重连                                             |
| ----------- | ----------------------------------------------------------------------- | ------- | -------------------- | ------- | ---------------------------------------------- |
| Bridge 主 WS | `core/core-network/.../BridgeWebSocketClient.kt`<br />参数在 `WsConfig.kt` | **60s**（对齐栅格） | 无（未设 `pingInterval`） | 10s     | 1s→60s 指数退避 ±20%                               |
| 微信 WS       | `app/.../wechat/WechatWsClient.kt`                                      | **60s**（对齐栅格） | **已移除**（原 20s）        | 30s     | 固定 2s ＋ 网络恢复即刻重连                               |
| 周期位置上报      | `app/.../report/ReportManager.kt`                                       | —       | —                    | —       | 10 分钟（服务端可配 1–120）+ WorkManager 兜底（系统下限 15 分钟） |

### 1.2 业务期常驻（服务运行期间）

| 连接   | 源码                                                        | 应用层心跳           | 传输层 ping | 判死                       | 重连                |
| ---- | --------------------------------------------------------- | --------------- | -------- | ------------------------ | ----------------- |
| 音频中继 | `app/.../relay/RelayClient.kt`<br />常量 `RelayProtocol.kt` | **30s** 文本 ping | 无        | 60s 无数据判死 + ready 超时 10s | 1/2/4/8/16/30s 退避 |
| VNT 组网（v0.2.32 改 1.x；v0.2.38 加断线自动重连） | `app/.../vnt/VntVpnService.kt`<br />官方 v1.2.9 `libvnt_jni.so`（Rust） | **黑盒**：周期在 Rust 内部、不可从 Java 侧调；Java 侧只是 `Vnt.awaitTimeout(2000)` 保活/停止信号循环，**不发网络包** | — | 实例停止由 `Vnt.stop()` 让 `awaitTimeout` 返回 true；错误经 `CallBack.error(ErrorInfo)` 上报 | **v0.2.38 监督重连**：可重试失败（Disconnect 等）worker 不 stopSelf，退避 **2/4/8/16/30/60s** 封顶（成功即重置）+ NetworkCallback 网络恢复立即重试；致命错误（TokenError/InvalidIp/授权被抢）转 ERROR 等人工；`START_NOT_STICKY` 进程被杀不复活 |

> **VNT 登记说明（v0.2.32 更新）**：这是第 6 条长连接，但心跳周期参数**不在 Kotlin 里**
> （1.x 由 Rust 内部维护，`awaitTimeout(2000)` 只是 Java 侧的保活/停止信号循环，**不发网络包**），
> 调本文档任何周期都影响不到它——升级 VNT 版本时才可能变化，届时需重新核实。
> 另外它还有一条「UI 侧 5 秒轮询」（`Vnt.list()` 读 native 侧缓存路由表，**本地内存读、不是网络 RPC**），
> **仅 App 在前台且处于组网页时运行**，退后台零请求、零定时器（详见
> `docs/research/vnt-mesh-integration.md` §十三），不计入常驻心跳耗电模型。
> ⚠️ v0.2.30/31 的 2.x 描述（QUIC `keep_alive_interval=5s` / `nextEvent()` / `getClientList` RPC）**已作废**。

### 1.3 页面级（进页面才连，退出即断）

| 连接          | 源码                                    | 应用层心跳   | 传输层 ping | 重连        |
| ----------- | ------------------------------------- | ------- | -------- | --------- |
| 销售 WS       | `app/.../sales/SalesWsClient.kt`      | **30s** | **20s**  | 固定 3s     |
| ESP32 开关 WS | `app/.../switch/RelaySwitchClient.kt` | 无       | **20s**  | 固定 3s     |
| 扫码上传 WS     | `app/.../scanner/ScanUploadClient.kt` | 连上即探测一次 | **15s**  | 5s→60s 退避 |

> **生命周期已源码核实（2026-11-01）**：`MainScreen.kt:158` 用 `when (selectedBottom)` 切 Tab，
> 切走即把旧页面移出组合树 → `DisposableEffect.onDispose` 触发 → 连接**彻底关闭**，
> **应用层心跳与 OkHttp `pingInterval` 同时停止**（`pingInterval` 只对活跃 WS 连接调度，close 后不再发）。
> 断连证据：销售 `SalesPages.kt:186`；ESP32 `RelaySwitchPages.kt:104`；扫码 `ScanPages.kt:672`。
> ⇒ 这三条在「一天只开几分钟」的实际使用习惯下**耗电可忽略**。
>
> ⚠️ **顺带发现的小不一致（未改）**：扫码页（661 行）用 `remember(uploadOn, uploadHostText)` 会判断上传开关；
> 但**会话列表页 `ScanSessionListPage`（1388 行）无条件创建并 start 上传客户端**，未判断 `uploadOn`。
> 仅在该页可见期间生效，影响小，但两处口径不一致。

### 1.4 常驻基线（不是心跳，但 24h 持续耗电）

| 项                 | 源码                                          | 默认    | 周期                       |
| ----------------- | ------------------------------------------- | ----- | ------------------------ |
| PARTIAL_WAKE_LOCK | `core-keepalive/.../ResourceHolder.kt:99` | **开** | 持有即 CPU 永不休眠             |
| WifiLock          | 同上 `:120`（`WIFI_MODE_FULL_HIGH_PERF`） | **开** | 持有即 WiFi 不进省电（**Android 14+ 已失效，见下**） |
| SilentPlayer 无声播放 | `core-keepalive/.../SilentPlayer.kt`        | **开** | 每 **20ms** 写一次 48kHz 音频帧 |
| JobService 存活窗口   | `core-keepalive/.../KeepAliveJobService.kt` | —     | `ALIVE_WINDOW_MS = 4s`   |

### 1.5 前端（不耗手机电，仅备案）

管理后台 `admin/src/views/connection/index.vue` 自动刷新轮询，默认 **3s**，可配。

---

## 二、各连接的周期硬约束（改之前先看这里）

| 连接                | 服务端超时                                                                                          | 客户端 Pong 超时           | 放宽的实际上限                                            |
| ----------------- | ---------------------------------------------------------------------------------------------- | --------------------- | -------------------------------------------------- |
| Bridge 主 WS       | `server_read_timeout=150s`（`config.yaml`，v0.2.20+ 由 45s 放宽）                                   | 10s（`WsConfig`）       | 间隔须 < 150s；当前 60s（2.5 倍余量）                         |
| 微信 WS             | **`CLIENT_HEARTBEAT_TIMEOUT=300s`**（center-server `core/connection_manager.py:16`，检测循环 60s 一轮） | 30s（`WechatWsClient`） | **间隔 < 300s 即可，服务端零改动**；当前 60s（5 倍余量）              |
| 销售 / 中继 / 开关 / 扫码 | 参考项目各有约定                                                                                       | —                     | 页面级连接，退出即断，耗电不敏感                                   |



> **重要纠正（v0.2.20 期间的错误结论）**：早前记录「微信心跳放宽到 30s 需服务端同步调 Pong 超时」
> 是错的。中心服务器 WS 主循环是裸 `while True: await websocket.receive_text()`（`api/routes/client.py:630`），
> **没有读超时**；唯一约束是 `CLIENT_HEARTBEAT_TIMEOUT = 300`。
> 因此 15s → 30s / 60s / 120s 全部合法且**服务端零改动**。
>
> 真正卡住「心跳上限」的不是服务端，是**运营商/路由器 NAT 空闲超时**（移动网络常见 30s–5 分钟，
> 家用/企业 NAT 常见 5–30 分钟）。间隔超过 NAT 超时 → 连接变「半死」，双向都收不到包，
> 只能等客户端 Pong 超时才能发现。

---

## 三、耗电模型（量级估算，非实测）

### 3.1 单次唤醒的无线增量

| 网络                | 单次孤立唤醒增量                | 说明                                          |
| ----------------- | ----------------------- | ------------------------------------------- |
| LTE 移动数据          | **约 1.5–4 J，取中值 2.5 J** | RRC 重建约 0.5 J ＋ 约 10s tail 维持约 2.0 J；弱信号可翻倍 |
| WiFi            | **约 0.05–0.3 J**        | 无 RRC 重建那种焦耳级开销；即便进 power save，单次唤醒仍比 LTE 低一个数量级 |

换算：1 mAh ≈ 13.9 J（3.85 V 锂电）。

> **关于「WiFi 已持 WifiLock ⇒ 射频常开」这个前提**：在 **Android 14（API 34）及以上已经不成立**。
> `WIFI_MODE_FULL_HIGH_PERF` 于 API 34 被废弃，平台**自动替换成 `WIFI_MODE_FULL_LOW_LATENCY`**，
> 而后者仅在「已连 AP ＋ **屏幕亮着** ＋ **App 在前台**」三条同时满足时才生效
> （见 `WifiManager` 官方文档）。⇒ 在 Bridge 最关心的「后台 ＋ 灭屏」场景里这把锁**根本不生效**。
> 分类：**Android 13 及以下**设备这把锁真生效、真耗电（关掉可省电）；
> **Android 14 及以上**它是 no-op（关掉省不了电，因为本来就没开）。它只对 WiFi 有效，也不防 Doze。

### 3.2 单条长连接的日耗电（按 5000 mAh 电池）

| 间隔   | 次/小时 | mAh/天（LTE） | 占电池   | mAh/天（WiFi） |
| ---- | ---- | ---------- | ----- | ----------- |
| 15s  | 240  | ~1036      | ~21%  | ~21         |
| 20s  | 180  | ~777       | ~16%  | ~16         |
| 30s  | 120  | ~518       | ~10%  | ~10         |
| 60s  | 60   | ~259       | ~5%   | ~5          |
| 120s | 30   | ~130       | ~2.6% | ~3          |
| 300s | 12   | ~52        | ~1%   | ~1          |

参考值：微信 Mars STN 自适应心跳下限约 **4.5 分钟**；FCM 长连接心跳建议 ~15 分钟（移动网络）。

### 3.3 三个必须知道的结论

1. **WiFi 场景下，调心跳间隔的收益很小。** 15s→30s 大约只省 **10–100 mAh/天**（0.2%–2%，
   取决于 Wi-Fi 是否真的在 power save）。**只有当设备跑在移动数据下，间隔才是关键变量。**
2. **常驻基线比心跳更值得优化。** `PARTIAL_WAKE_LOCK` + `WifiLock` + `SilentPlayer`（20ms/tick）
   三者叠加，量级约 **400–1500 mAh/天**，与 15s 心跳同数量级甚至更大。
   其中 SilentPlayer 每 20ms 唤醒一次写音频帧，是三者里最激进的一项。
3. **合并心跳（coalescing）的收益 > 放宽间隔。** 多条连接的心跳若错相，各自独立触发一次无线唤醒；
   对齐到同一时刻后共用一次唤醒。当前进程级就有 3 个计时器（15s + 20s + 20s），
   错相约 600 次唤醒/小时，对齐到 20s 栅格后约 180 次/小时，**省约 70% 且零可靠性损失**。

---

## 四、已识别的优化点（按性价比排序）

> **状态更新（2026-11-01）**：第 1、2、3 项**已实施**，实际取值改为「微信 60s / Bridge 60s / 双端对齐 60s 栅格」
> （比原计划的 30s 更激进，因用户确认不需考虑版本兼容）。完整改前/改后与回滚配方见 **§8**。
> 第 4、5 项**未实施**，见 §8.12。

| # | 优化                                                                                                                                    | 预期收益                        | 风险                                                                   |
| - | ------------------------------------------------------------------------------------------------------------------------------------- | --------------------------- | -------------------------------------------------------------------- |
| 1 | **去掉双层心跳冗余**：保留应用层 ping（能测 RTT、能判应用存活），把 `pingInterval` 设为 0 或 ≥ 2×间隔作为兜底。**实际只对微信 WS 有意义**——销售/开关/扫码是页面级，退出即断（见 §1.3），冗余开销≈0 | 微信这一条：唤醒次数减半 | 低。需确认对端会自动回 WS ping 帧（OkHttp 会，center-server 走 starlette/uvicorn 也会） |
| 2 | **心跳对齐**：把进程级连接的心跳对齐到同一相位（如整 20s 栅格）                                                                                                  | 省约 70% 无线唤醒                 | 低                                                                    |
| 3 | **微信间隔 15s → 30s**（服务端零改动，Pong 超时 60s 已够）                                                                                             | LTE 下省约 518 mAh/天；WiFi 下可忽略 | 低。30s 仍远小于服务端 300s 与常见 NAT 超时                                        |
| 4 | **SilentPlayer 降频 / 默认关**：20ms tick → 100ms+，或仅在检测到进程被压制时启用                                                                           | 常驻基线里最大的单项                  | 中。它就是为抗杀而设计的，降频可能削弱保活效果，需真机对照                                        |
| 5 | 微信间隔放宽到 60s/120s                                                                                                                      | LTE 下再省约 260–390 mAh/天      | 中。需把客户端 `PONG_TIMEOUT_MS` 同步调到 ≥ 2×间隔；且要实测 NAT 是否撑得住                 |

---

## 五、怎么实测（不要靠模型拍板）

```bash
# 1. 重置统计（选移动数据网络，关 WiFi，拔掉 USB 或用无线调试）
adb shell dumpsys batterystats --reset
# 2. 亮屏一次确认 App 已启动 + 保活服务在跑，然后熄屏静置 N 小时
# 3. 取报告
adb shell dumpsys batterystats > batterystats.txt
# 4. 关键看这几段：
#    - Estimated power use (mAh)  里的 my.bridge.com 汇总
#    - Per-app mobile ms / radio active ms
#    - Wake lock 持有时长（应接近 100%，因为默认开）
```

更精确的做法：

- **对照法**：同一台设备、同一网络、熄屏静置 8 小时，分别在「心跳 15s」「心跳 30s」「心跳 60s」下跑，
  比较 `batterystats` 里 `radio active` 与总 mAh 的差值。这是唯一可信的数字。
- **区分基线与心跳**：把 `WechatRuntime` 整体停掉（保留保活服务）跑一轮，
  再只停心跳（保持连接）跑一轮，两次差值即为心跳的真实净成本。
- 用 Battery Historian 看 `wake_lock_in` / `mobile_radio` 的条形密度，能直观看到对齐效果。

---

## 六、保活三锁的语义与取舍

设置页文案（`SettingsPages.kt:205/206/212`）：「⚡ CPU 唤醒锁」「📡 WiFi 高性能锁」「🔇 无声播放」，
对应 `ResourceHolder.acquireWakeLock` / `acquireWifiLock` 与 `SilentPlayer`，由
`KeepAliveService.onCreate → applyKeepAlive → ResourceHolder.acquireAll` 触发，三者默认全开。

### 6.1 PARTIAL_WAKE_LOCK（CPU 唤醒锁）

只做一件事：**阻止 CPU 进入深睡**。它不是"能不能联网"的开关。

**取消后的三个独立后果，必须分开看：**

| 维度 | 结论 |
| --- | --- |
| 收推送（下行） | **仍能收到。** 入站数据包由射频固件唤醒 AP，这是 Wi-Fi/蜂窝的固有能力，与 WakeLock 无关 |
| 发心跳（上行） | **发不出去。** CPU suspend 后用户态定时器全部冻结（`Handler.postDelayed` / `coroutine delay` / OkHttp ping 调度器都不再按点触发），只有 AlarmManager `RTC_WAKEUP`、JobScheduler、FCM 能唤醒系统 |
| 后台网络限制 | 取决于 **Doze**，不取决于 WakeLock。而 Doze 的进入条件之一就是"无 App 持 wakelock"——所以现在持锁实际上**顺带挡住了 Doze**；取消锁后 App 暴露在 Doze 之下，此时能否收包**完全看是否在电池优化白名单里**（白名单豁免 Doze，网络不受限） |

**最阴险的失效模式**：取消锁 → 心跳发不出 → NAT 映射过期（移动网 30s–5 分钟）→ 连接变"半死"。
此时客户端 `socket != null` 自认在线，服务端推的包被 NAT 丢弃，**收不到且不知道自己收不到**，
要等客户端 Pong 看门狗（微信 60s）或 TCP 超时才发现。

**还能收到 WS 消息吗（分档）**：
- 白名单在 + 心跳发得出去 → 能收
- 白名单在 + 无心跳 → 几十秒~几分钟后开始收不到，且静默失效
- 无白名单 + Doze → 维护窗口前收不到，延迟可达数分钟~十几分钟
- 进程被杀 → 完全收不到（这是保活要解决的另一回事）

**真正能断网的另有其人**：省流量模式（Android 7+，只限移动数据）、App Standby（限 Job/Alarm 频率，不断网）、
国产 ROM 的"后台联网"独立开关（MIUI/ColorOS 有，关掉则后台 socket 直接被切）。

> 设置页自己的说明（`SettingsPages.kt:374`）已写明：「CPU 唤醒锁为核心手段，关闭会明显降低保活效果」。

### 6.2 WifiLock（WiFi 高性能锁）

`WIFI_MODE_FULL_HIGH_PERF`：告诉 Wi-Fi 固件"别进省电"。关掉后**不是连不上、也不是明显变慢**——
能正常连、正常收发，只是射频可以在空闲时短暂打盹，唤醒时多几十毫秒级延迟，对长连接保活几乎无感。

**但 Android 14（API 34）起它已被平台自动替换成 `WIFI_MODE_FULL_LOW_LATENCY`**，
后者仅在「已连 AP ＋ 屏幕亮着 ＋ App 在前台」时生效 ⇒ **后台灭屏场景下这把锁不生效**（详见 §3.1 注）。

### 6.3 SilentPlayer（无声播放）

`AudioTrack` 循环播放 ±1 LSB 近静音 PCM，48 kHz，每 **20ms** 写一次帧（`TICK_MS = 20L`）。
不申请 AudioFocus、不改系统音量、只 pause+flush 绝不 stop。目的：抬高进程优先级抗杀。
三者里**最激进**的一项——每 20ms 唤醒一次写音频帧，直接阻止任何深度空闲。
代价是明确的持续耗电；收益是抗杀（正是本项目保活的核心诉求）。降频需真机对照，不可拍脑袋。

---

## 七、更新纪律

改动任何一处周期，必须同步：

1. 更新本文档 §1 对应表格；
2. 检查 §2 的约束（服务端超时、客户端 Pong 超时、NAT）；
3. 若涉及 Bridge 主 WS，同时核对 `docs/api/ws-client.md` 与 `server/config.yaml` 的
   `heartbeat.interval` / `client_pong_timeout` / `server_read_timeout` 三者一致性
   （**该组参数按约定双端硬编码、无配置入口，见 `WsConfig` 注释**）。

---

## 八、变更台账 v1：心跳放宽 + 去双层冗余 + 相位对齐（2026-11-01）

> **本节的唯一目的**：把「改之前是什么样」逐字固化，配合「改之后是什么样」与回滚配方，
> 做到任何一步出问题都能 **100% 精确还原**。改前状态在动手前记录，改后状态在改完核对。
>
> 背景决策：用户确认**不需要考虑版本兼容**（每次大更新强制用户升到最新版、服务端同步更新），
> 因此采用 60s 方案，服务端 `server_read_timeout` 同步放宽。

### 8.0 回滚总配方（最优先看这里）

> **2026-11-01 09:2x 更新**：微信集成已提交（commit `1036b54`），**本次涉及的文件现已全部入库**，
> git 均可一键还原，不再有「兜不住」的文件。下方配方已同步指向新基线 `1036b54`。

**全部文件（git 可一键还原）**：`WsConfig.kt`、`BridgeWebSocketClient.kt`、`SilentPlayer.kt`、
`WechatWsClient.kt`、`server/config.yaml`、`server/app/config.py`、`server/tests/test_config.py`、
`docs/api/ws-client.md`、`docs/server/design.md`、`docs/CHANGELOG.md`、`docs/INDEX.md`。

**方式一：整提交回滚（推荐，心跳改动是独立 commit）**

```bash
git revert 555d0f6             # 心跳改动的独立 commit；反向提交可保留历史
                               # 若需彻底抹掉（连同工作区回到改前）：git reset --hard 1036b54
```

**方式二：按文件精确还原（保留微信集成等其他改动）**

```bash
git checkout 1036b54 -- core/core-network/src/main/java/my/bridge/com/core/network/ws/WsConfig.kt
git checkout 1036b54 -- core/core-network/src/main/java/my/bridge/com/core/network/ws/BridgeWebSocketClient.kt
git checkout 1036b54 -- core/core-keepalive/src/main/java/my/bridge/com/keepalive/SilentPlayer.kt
git checkout 1036b54 -- server/config.yaml server/app/config.py server/tests/test_config.py
git checkout 1036b54 -- docs/api/ws-client.md docs/server/design.md docs/INDEX.md
# docs/CHANGELOG.md 的心跳里程碑段落需手工删除（该文件还含微信集成条目，不能整体还原）
# 新增的台账文档本身不受影响；如需删除：rm docs/dev/heartbeat-inventory.md
```

**⚠️ 例外（git 还原不了，必须手工）：`app/src/main/java/my/bridge/com/wechat/WechatWsClient.kt`**

原因：`wechat/` 整个目录此前一直是 untracked，它**第一次入库就是在 commit `1036b54`、
且入库时已带上本次心跳改动**（15s→60s、去 pingInterval、修 sendPing）。
也就是说 **git 历史里从来不存在该文件的改前版本**——`git checkout 1036b54 -- <该文件>`
拿到的是**改后**代码，不能用于回滚。

还原该文件只能按 **§8.4 的改前原文逐字抄回**（改回 `HEARTBEAT_MS = 15_000L`、
`PONG_TIMEOUT_MS = 60_000L`、恢复 `.pingInterval(20, TimeUnit.SECONDS)`、恢复原 `sendPing()`）。
这是本项目当前**唯一**一个 git 兜不住的心跳改动点，务必留意。

**方式三：手工还原（git 不可用时的兜底）**：按 §8.2~8.8 的改前原文逐字抄回。

### 8.1 改前基线总览（2026-11-01 09:15 实测）

| 位置 | 改前值 |
| --- | --- |
| `WsConfig.HEARTBEAT_INTERVAL_MS` | `20_000L`（20s） |
| `WsConfig.PONG_TIMEOUT_MS` | `10_000L`（10s） |
| `WechatWsClient.HEARTBEAT_MS` | `15_000L`（15s） |
| `WechatWsClient.PONG_TIMEOUT_MS` | `60_000L`（60s） |
| `WechatWsClient` OkHttp `pingInterval` | `20, TimeUnit.SECONDS`（**已开启**） |
| `server/config.yaml` heartbeat.interval | `20` |
| `server/config.yaml` server_read_timeout | `45` |
| `server/app/config.py` 同上默认值 | `20` / `45` |
| `server/tests/test_config.py:26-27` 断言 | `== 20` / `== 45` |
| 心跳相位 | **无对齐**，各连接按各自连上时刻起算 |
| 进程级无线唤醒（估） | 微信应用 240/h ＋ 微信传输 180/h ＋ Bridge 180/h ≈ **600 次/小时** |

### 8.2 改前原文 · WsConfig.kt（L11 / L30-34）

```kotlin
// 文件头注释 L11：
 *   heartbeat.interval=20s、client_pong_timeout=10s、reconnect.base=1s/max=60s/jitter=0.2。

    /** 心跳发送周期（毫秒）。与服务端 heartbeat.interval=20s 硬编码一致。 */
    const val HEARTBEAT_INTERVAL_MS = 20_000L

    /** 发送 ping 后等待 pong 的超时（毫秒）。与服务端 client_pong_timeout=10s 一致。 */
    const val PONG_TIMEOUT_MS = 10_000L
```

### 8.3 改前原文 · BridgeWebSocketClient.kt（L292）

```kotlin
                delay(WsConfig.HEARTBEAT_INTERVAL_MS)
```

### 8.4 改前原文 · WechatWsClient.kt（未跟踪文件，逐字备份）

```kotlin
    companion object {
        private const val TAG = "微信WS"
        private const val HEARTBEAT_MS = 15_000L
        private const val PONG_TIMEOUT_MS = 60_000L
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // 传输层 ping 兜底：尽早发现半死连接（照 SalesWsClient 的做法）
        .pingInterval(20, TimeUnit.SECONDS)
        .build()
```

```kotlin
    private fun sendPing() {
        handler.removeCallbacks(heartbeat)
        if (!send(JSONObject().put("type", "ping"))) return
        // Pong 超时：60s 内没收到 pong 判定连接已死
        val watchdog = Runnable {
            DiagLog.w(TAG, "Pong 超时，判定连接已死")
            teardown()
            listener?.onDisconnected("Pong 超时")
        }
        pongWatchdog = watchdog
        handler.postDelayed(watchdog, PONG_TIMEOUT_MS)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }
```

```kotlin
    private fun scheduleHeartbeat() {
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, HEARTBEAT_MS)
    }
```

> ⚠️ **改前已存在的缺陷（必须修，且与去传输层 ping 强相关）**：
> `sendPing()` 里 `if (!send(...)) return` —— 发送失败时 `heartbeat` 已被 `removeCallbacks` 移除
> 且**不再重排**，心跳永久静默停止。改前有传输层 20s ping 兜底尚可掩盖；
> 去掉传输层 ping 后必须改掉，否则心跳一停就只剩服务端 300s 才踢才发现。

### 8.5 改前原文 · SilentPlayer.kt（L160-170）

```kotlin
    /** 生成近静音帧：最低位 ±1 抖动，避免全 0 帧被判空流回收 */
    private fun nextFrame(): ByteArray {
        val buffer = ByteArray(FRAME_BYTES)
        var index = 0
        while (index < FRAME_BYTES) {
            val low = if ((counter++ and 1L) == 0L) 1 else 0xFF
            buffer[index] = low.toByte()
            buffer[index + 1] = 0.toByte()
            index += 2
        }
        return buffer
    }
```

> 改前每个 tick 都 `new ByteArray(1920)`：20ms tick ⇒ 50 次/秒 ⇒ **约 96 KB/s ≈ 8.3 GB/天** 的对象分配。

### 8.6 改前原文 · server/config.yaml（L12-15）

```yaml
heartbeat:
  interval: 20                   # 客户端心跳发送周期（秒）
  client_pong_timeout: 10        # 客户端 ping 后等 pong 超时（秒）
  server_read_timeout: 45        # 服务端读超时（秒），须 > interval（建议 > 2 倍）
```

### 8.7 改前原文 · server/app/config.py（L42-44）与 tests/test_config.py（L26-27）

```python
    interval: float = Field(default=20, gt=0, description="客户端心跳发送周期（秒）")
    client_pong_timeout: float = Field(default=10, gt=0, description="客户端 ping 后等 pong 超时（秒）")
    server_read_timeout: float = Field(default=45, gt=0, description="服务端读超时（秒）")
```

```python
    assert s.heartbeat.interval == 20
    assert s.heartbeat.server_read_timeout == 45
```

> 注意：`get_settings()` 会**先加载 `server/config.yaml`**（`config.py:272`），
> 所以 `test_default_settings` 实际断言的是 config.yaml 的值 —— 改 config.yaml 必然要同步改这两行断言。

### 8.8 改前原文 · 文档

| 文件 | 改前 |
| --- | --- |
| `docs/api/ws-client.md:88` | 「每 `20s` 发送 `ping`；`ping` 后 `10s` 内未收到 `pong` …」 |
| `docs/api/ws-client.md:164` | 表格「心跳周期 \| 20s \| 客户端发 ping 周期；服务端读超时 45s > 2×20s」 |
| `docs/server/design.md:71-73` | 表格 `interval` 20s / `client_pong_timeout` 10s / `server_read_timeout` 45s |
| `docs/server/design.md:109` | 「（服务端侧：45s 未收任何消息）」 |
| `docs/server/design.md:349-350` | 示例 `client_pong_timeout: 10` / `server_read_timeout: 45` |

---

### 8.9 改后状态（2026-11-01 09:2x，改动完成后实测核对）

| 位置 | 改前 | **改后** |
| --- | --- | --- |
| `WsConfig.HEARTBEAT_INTERVAL_MS` | 20_000L | **60_000L** |
| `WsConfig.PONG_TIMEOUT_MS` | 10_000L | **10_000L（未动）** |
| `WsConfig.delayToNextGrid()` | 不存在 | **新增**（对齐栅格） |
| `BridgeWebSocketClient` 心跳 delay | `delay(HEARTBEAT_INTERVAL_MS)` | **`delay(WsConfig.delayToNextGrid())`** |
| `WechatWsClient.HEARTBEAT_MS` | 15_000L | **60_000L** |
| `WechatWsClient.PONG_TIMEOUT_MS` | 60_000L | **30_000L** |
| `WechatWsClient` OkHttp `pingInterval` | 20s（开启） | **已移除**（协议层不再单独 ping） |
| `WechatWsClient.sendPing()` | 发送失败即停跳（缺陷） | **无论成败都重排下一栅格** |
| `WechatWsClient.scheduleHeartbeat()` | 固定 `HEARTBEAT_MS` | **`delayToNextGrid()`** |
| `SilentPlayer.nextFrame()` | 每 tick `new ByteArray(1920)` | **复用 `frameBuffer`** |
| `server/config.yaml` interval / server_read_timeout | 20 / 45 | **60 / 150** |
| `server/app/config.py` 同默认值 | 20 / 45 | **60 / 150** |
| `server/tests/test_config.py:26-27` | == 20 / == 45 | **== 60 / == 150** |
| 心跳相位 | 无对齐 | **Bridge 与微信共用 Unix 毫秒 60s 栅格** |
| 进程级无线唤醒（估） | ~600 次/小时 | **~60 次/小时（−90%）** |
| 死连接最长发现窗口（微信） | 15+60 = 75s | **60+30 = 90s** |
| 死连接最长发现窗口（Bridge） | 20+10 = 30s | **60+10 = 70s** |

### 8.10 改后原文（逐字，供核对 / 反向还原）

**WsConfig.kt**
```kotlin
 *   heartbeat.interval=60s、client_pong_timeout=10s、reconnect.base=1s/max=60s/jitter=0.2。
 *   （v0.2.20+ 心跳由 20s 放宽到 60s，服务端 server_read_timeout 同步 45s → 150s；
 *    详见 docs/dev/heartbeat-inventory.md §8）

    /** 心跳发送周期（毫秒）。与服务端 heartbeat.interval=60s 硬编码一致。 */
    const val HEARTBEAT_INTERVAL_MS = 60_000L

    /**
     * 距下一个心跳栅格的毫秒数（取值区间 (0, HEARTBEAT_INTERVAL_MS]）。
     * ...（对齐说明见源文件）
     */
    fun delayToNextGrid(nowMs: Long = System.currentTimeMillis()): Long =
        HEARTBEAT_INTERVAL_MS - (nowMs % HEARTBEAT_INTERVAL_MS)
```

**BridgeWebSocketClient.kt（原 L292）**
```kotlin
                // 对齐到心跳栅格：与微信 WS 共用一次无线唤醒（见 WsConfig.delayToNextGrid）
                delay(WsConfig.delayToNextGrid())
```

**WechatWsClient.kt（未跟踪文件，改后关键片段）**
```kotlin
        private const val TAG = "微信WS"
        // 心跳 60s：服务端 CLIENT_HEARTBEAT_TIMEOUT=300s，留 5 倍余量，服务端零改动即可。
        private const val HEARTBEAT_MS = 60_000L
        // Pong 超时 30s（原 60s）：随间隔放宽同步收紧，把「连接静默死亡」的最长发现窗口
        // 从 60+60=120s 压回 60+30=90s。30s 对任何合理的 WS 往返仍是极大余量。
        private const val PONG_TIMEOUT_MS = 30_000L
```

```kotlin
    private fun sendPing() {
        handler.removeCallbacks(heartbeat)
        // 【修复】无论本次发送成功与否都必须重排下一次心跳。
        val sent = send(JSONObject().put("type", "ping"))
        if (sent) {
            val watchdog = Runnable {
                DiagLog.w(TAG, "Pong 超时，判定连接已死")
                teardown()
                listener?.onDisconnected("Pong 超时")
            }
            pongWatchdog = watchdog
            handler.postDelayed(watchdog, PONG_TIMEOUT_MS)
        } else {
            DiagLog.w(TAG, "心跳发送失败，等待下一栅格重试（不再依赖传输层 ping）")
        }
        handler.postDelayed(heartbeat, delayToNextGrid())
    }

    private fun scheduleHeartbeat() {
        handler.removeCallbacks(heartbeat)
        handler.postDelayed(heartbeat, delayToNextGrid())
    }

    private fun delayToNextGrid(): Long =
        HEARTBEAT_MS - (System.currentTimeMillis() % HEARTBEAT_MS)
```

```kotlin
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // 传输层 ping（OkHttp pingInterval）已于 v0.2.20+ 移除：① 对保活 NAT 与应用层 ping
        // 完全重复；② WS ping 帧由 uvicorn 协议层自动回 pong，传不到 ASGI 应用层，
        // 不会刷新 center-server 的 last_ping（client.py:635）。
        .build()
```

**SilentPlayer.kt**
```kotlin
    private val frameBuffer = ByteArray(FRAME_BYTES)

    private fun nextFrame(): ByteArray {
        val buffer = frameBuffer
        var index = 0
        while (index < FRAME_BYTES) {
            val low = if ((counter++ and 1L) == 0L) 1 else 0xFF
            buffer[index] = low.toByte()
            buffer[index + 1] = 0.toByte()
            index += 2
        }
        return buffer
    }
```

### 8.11 验证结果

| 验证项 | 结果 |
| --- | --- |
| `./gradlew :app:compileDebugKotlin` | **BUILD SUCCESSFUL**（JDK 17，2026-11-01） |
| `uv run pytest tests/test_config.py`（server/） | **5 passed** |
| 旧值残留扫描（代码侧） | 无。`SalesWsClient` 30s 为**页面级**保持原样；`CommandModule.CAPTURE_TIMEOUT_MS=20_000` 与心跳无关 |

### 8.12 仍未做 / 待真机验证

1. **真机实测**：按 §5 对照法测「改前 vs 改后」的日耗差；重点确认 60s 心跳在实际运营商 NAT 下是否稳定
   （若出现连接频繁静默死亡，说明该运营商 NAT 超时 < 60s，应回退到 30s）。
2. **相位对齐效果验证**：`adb shell dumpsys batterystats` 观察 `mobile_radio` 条形是否收敛成 1 分钟一次的规律脉冲。
3. SilentPlayer **本次未动 tick**（仍是 20ms），只做了缓冲区复用。见 §6.3 与 §3.3：调 tick 收益仅占其自身成本 10–25%，
   建议先做「开 / 关」真机对照再决定是否动。

### 8.13 触发回滚的信号

出现任一情况，按 §8.0 回滚并回到 20s / 15s：

- 管理后台设备频繁上下线（服务端读超时误判）
- 微信消息延迟明显变长或长时间收不到（NAT 超时 < 60s）
- 日志出现 `Pong 超时` 频率显著上升
- 设备待机耗电不降反升（说明重连次数增加抵消了心跳收益）

---

## 九、VNT 1.x 空闲发包取证 + 组网耗电增量评估（2026-11-02）

> 触发：用户真机 v0.2.32 组网成功、局域网互访已通，问「开 / 不开组网的耗电差多少」。
> 本节**只追加、不改动上文任何原文**（保留改前留档）。§1.2 说 VNT 周期「黑盒、不可从 Java 侧调」
> 依旧成立，但周期数值现已从 1.x 源码取到确数，登记于此避免日后重复调研。

### 9.1 取证来源与版本歧义

线上 `github.com/lbl8603/vnt` 的 tag 归档（codeload / raw 经 ghproxy 镜像），
对 `1.2.9`、`v1.2.9`、`v1.2.14`、`v1.2.17` 四个 tag 逐 tag 比对。
⚠️ **存在两个同名 1.2.9 tag**：`1.2.9`(sha `8a4d218`，旧 `Context` 架构) 与
`v1.2.9`(sha `4dca4c4f`，`ChannelContext` 架构)。官方 raw/codeload 的 `v1.2.9` 是后者；
本项目 `libvnt_jni.so` 具体对应哪一个**无法从二进制判定**，故下表两个版本分别列出。

### 9.2 1.x 空闲期周期任务（★ 心跳 3s 是硬编码，无条件发，不可配）

| 任务 | 周期 | 源码（1.x） |
| --- | --- | --- |
| **心跳**（发往网关 + 每条路由 + 每个在线 peer） | **3s**，四 tag 一致 | `vnt/src/handle/maintain/heartbeat.rs:34-35` `Duration::from_secs(3)`，注释「心跳包 3秒发送一次」；`:27-35` 无条件先发再重排 |
| 中继路径探测 | 首 30s，之后 30s | `heartbeat.rs:133`、`:157` |
| 状态上报（仅 `v1.2.9+`） | 首 60s，之后 600s | `vnt/src/handle/maintain/up_status.rs:21`、`:47` |
| P2P 打洞 | `v1.2.9`：**[5,6,7]s 循环**，离线 5s；`1.2.9`：质数序列 3,5,7,11,13,17,19,23,29 | `vnt/src/handle/maintain/punch.rs:192-195` / `:131-135` |
| AddrRequest / STUN 刷新 | `v1.2.9`：**1s**（各通道轮转）/ **19s**（最后一通道）/ 对称 NAT 且已获公网端口降频 **600s**；`1.2.9`：固定 **9s** | `vnt/src/handle/maintain/addr_request.rs:43`、`:57`、`:61` / `:22-23` |
| NAT 类型重测 | 600s | `vnt/src/handle/maintain/re_nat_type.rs:10,18` |
| 路由判死 | 10s 无数据 | `vnt/src/core/conn.rs:315`、`vnt/src/channel/idle.rs:35` |
| 空闲检查本地定时器（**不发网络包**） | 100ms / 3000ms | `vnt/src/handle/maintain/idle.rs:112,115` |

**结论**：1.x **没有任何全局「空闲停止发包 / 休眠 / 省电」机制**（`heartbeat.rs:27-35`、
`vnt/src/util/scheduler.rs:58-67` 均无空闲判定），README 也**没有心跳间隔配置项** ⇒
3s 是 Rust 侧硬编码，Java `Config` 与本项目的 `VntConfigBuilder` 都改不了它。
只有局部降频（离线不探测、通道数够不再打洞、对称 NAT 降频 600s），不影响 3s 主心跳。

> 对照：本地 `references/vnt/` 全为 2.x（心跳 5~15s 量级），**不可套用到 1.x**。

### 9.3 为什么增量不是「整机流量翻倍」

`VntVpnService.generateTun()` 只 `addRoute(虚拟网段)`，**没有 `0.0.0.0/0`**（`:250-256`）。
⇒ 其它 App 流量、Bridge 自身的主 WS / 微信 WS / 销售 / 扫码连接**都不经过 TUN**。
增量仅限 VNT 自己发的包与 native 线程 CPU；空闲时 TUN 零流量。
前台「5 秒刷新」走 `Vnt.list()` 本地内存读，且仅前台组网页运行（§1.2），**不计入**。

### 9.4 加密的额外 CPU

项目 `VntConfigBuilder` 默认 `cipherModel=aes_gcm` / `serverEncrypt=true`（对齐官方 `AddActivity.save()`）；
1.x 源码默认 `serverEncrypt=false`、无 password 时 `Cipher::None` 是 **no-op**（`vnt/src/cipher/cipher.rs:286`）。
心跳包虽调 `encrypt_ipv4()`，但只有设了密码 / 开 `serverEncrypt` 才真加密。
本项目开了 `serverEncrypt` ⇒ 服务端通道有 AES 开销，包很小、频率虽高但 CPU 成本仍是次要项。

### 9.5 耗电增量估算（量级，非实测；分母 = 5000 mAh 电池）

沿用 §3.1 单次唤醒模型（LTE 2.5 J / WiFi 0.15 J，1 mAh ≈ 13.9 J），
**基线** = 不开通组网：进程级 ~60 次/小时（双 WS 60s 对齐栅格）+ 保活三锁常驻基线 400–1500 mAh/天。

| 场景（空闲、无业务流量、单 peer） | VNT 净增量 | 占整机 | 主导项 |
| --- | --- | --- | --- |
| **WiFi 常驻** | **约 +100~300 mAh/天** | **2%~6%** | 3s 心跳把 WiFi 从 PSM 拽成近持续活跃 |
| **LTE 移动数据** | **约 +1000~1800 mAh/天** | **20%~36%** | 射频长期停在 RRC Connected，无法回落 |
| 有业务流量（互访/传文件，如 10 Mbps） | 再 **+50~150 mW**（约 +300~900 mAh/天·持续传输） | 视时长 | TUN 每包内核↔用户态拷贝 + AES-GCM；中继模式流量再翻倍 |
| 前台停在组网页 | 近似 0 增量 | — | `Vnt.list()` 本地读，5s 定时器仅前台 |

> **⚠️ 模型边界（必须知道）**：§3.2 的线性表只在「间隔 > RRC tail ≈ 10s」时成立。
> VNT 是 **3s < 10s tail** ⇒ LTE 下射频**不会每次重建**，而是持续停在高功耗态，
> 线性外推的 1200 次/h × 2.5 J ≈ 5180 mAh/天 是**物理上不成立的虚高值**。
> 饱和上限应按「常驻功率差」算：LTE 持续活跃约 200~300 mW，扣待机基线后即上表量级。
> 这也意味着：**在 LTE 下把 VNT 心跳放宽到 > 10s 的收益，远大于在 WiFi 下调任何参数。**

### 9.6 与现有基线的叠加关系（别重复计算）

- Bridge 默认持有 `PARTIAL_WAKE_LOCK`，CPU 本来就不休眠 ⇒ VNT 的 native 定时器
  **不产生额外的「CPU 唤醒」成本**，只占用 CPU 时间片（粗估常驻 +5~20 mW）。
- VNT 前台服务未额外申请 WakeLock（`VntVpnService` 源码核对），通知常驻本身近零耗电。
- 因此增量几乎全在**射频**，不是 CPU、不是内存、不是 APK 体积。

### 9.7 把估算收敛成实测（当前 `adb devices` 为空，未实测）

```bash
# 用项目自带 adb（PATH 上的 adb 是古董版，见项目记忆/配置）
F:/pythonWork/appClient/tools/adb/platform-tools/adb.exe devices

# ① 测 VNT 真实空闲发包频率（最关键的不确定量）
adb shell dumpsys netstats --reset        # 熄屏静置，组网开 / 关各跑 1 小时
adb shell dumpsys netstats detail | grep -i <bridge_uid>   # 看 tx/rx packets 差值
#   预期：3s 心跳 ⇒ 约 1200 包/小时（×peer 数），关组网时约 60 包/小时

# ② 测整机与 App 耗电（对照法，唯一可信）
adb shell dumpsys batterystats --reset   # 熄屏静置 8h，两轮条件完全一致
adb shell dumpsys batterystats > bs_on.txt   # 组网开
#   重点看 Estimated power use(mAh) 里 my.bridge.com、mobile/wifi radio active ms
```

更硬的手段：外接电流计 / 支持 `current_now` 的机型读 `/sys/class/power_supply/battery/current_now`。
真机对照时务必固定网络（纯 WiFi 或纯 LTE）、熄屏、关闭其它 App 后台、两轮时长一致。

### 9.8 若将来要省电（**本轮未改任何代码**，仅登记方向）

1. **最有效**：让 VNT 只在 WiFi 下常驻、移动数据下自动断开（或提示用户），绕开 LTE 最大头。
2. 3s 心跳在 Rust 内部硬编码 ⇒ 从 Java / Kotlin 侧**无法调**；要改只有三条路：换 so 版本、
   自编 JNI（违反「不擅自本地安装程序」硬约束）、或接受它。
3. 本项目双 WS 已对齐 60s 栅格，但 **VNT 的 3s 心跳无法与之对齐**（native 独立计时），
   所以 §3.3「合并心跳收益 > 放宽间隔」这条优化**对 VNT 不适用**。
4. 禁用打洞（`punchModel` 留空走底层默认）只能省掉打洞探测包，省不掉 3s 主心跳，收益有限。

### 9.9 口径澄清 + 心跳启动/停止条件（2026-11-02 追问补充，只追加不改原文）

**① §9.5 表格「前台停在组网页 ≈0」的口径（易误读，以此为准）**
该行**不是**「App 在前台就零耗电」，而是说「**前台停在组网页** 相对 **组网开着但退到后台**
几乎不增加耗电」——5s 刷新走 `Vnt.list()` 本地内存读，退后台即取消定时器。
把它与 §9.5 前两行（「开组网 vs 不开组网」的净增量）并列属**口径混用**，应单独看。
实际恰恰相反：**前台亮屏时整机耗电远高于后台**（屏幕 300~800 mW + Compose 渲染/重组），
且 **VNT 的 3s 心跳与前后台无关**（前台 / 后台 / 锁屏都照样发）。正确对照：

| 状态 | 3s 心跳 | 整机耗电 |
| --- | --- | --- |
| 后台熄屏 + 组网开 | 一直发 | 基线 + VNT 增量 |
| 前台亮屏 + 组网开 | 一样发 | 更高（屏幕 + UI） |
| 前台组网页 vs 后台：VNT 模块额外增量 | — | ≈0 |

**② 心跳不是 REST / HTTP**：是 Rust 硬编码的 VNT 自有 **UDP 控制包**
（`Protocol::Control` + `control_packet::Protocol::Ping`，`12+4+ENCRYPTION_RESERVED` 字节，
`heartbeat.rs:211-225`），非 HTTP / JSON / WebSocket。§9.2 的「Rust 硬编码」勿读作 REST。

**③ 启动 / 停止条件（v1.2.9 源码取证）**
`Vnt(config,callback)` → native `new0` → `ChannelContext::new` 里
`scheduler.timeout(3s, start)`（`vnt/src/core/conn.rs:256`）→ 3 秒后 `start()` 第一件事
`maintain::heartbeat(...)`（`conn.rs:306`）→ `heartbeat()` **先立刻发一轮** `heartbeat0()`，
再 `scheduler.timeout(3s, 递归)`（`heartbeat.rs:27-44`），无条件循环。
⇒ **从「开始组网」约 3 秒后，到「停止组网」为止，每 3 秒一轮**；
与是否连上服务端、有无业务流量、App 前后台 / 锁屏**全部无关**。
停止：`Vnt.stop()` → `stop_manager.stop()`（`conn.rs:415-416`）→ Scheduler listener 触发
（`util/scheduler.rs:45`）→ `timeout` 返回 false → 循环退出。
本项目 `START_NOT_STICKY` ⇒ 进程被杀也不复活，心跳随之停止。

**④ 没连上服务器时会不会发？会，而且不是「断线才开始发」**
`heartbeat0()` 给网关那次**没有 offline 检查**（`heartbeat.rs:61-73`），UDP 下
`send_default` 直接 `send_main_udp(...)` 真发包（`channel/context.rs:194-200`），
服务端不可达只是没回应。只有两处离线跳过：`heartbeat.rs:105`（在线但无路由的 peer 探测）、
`heartbeat.rs:172`（`client_relay0` 中继探测）。
且断线时另有更密的包：`idle` 5s 网关检查触发握手重连、`punch` 5/6/7s 打洞、
`addr_request` 1s/19s STUN ⇒ **断线不省电，可能更耗**。
心跳的作用是保 NAT/UDP 映射、维持 P2P 路由（10s 无数据判死，`conn.rs:315`）、
下发节点列表 epoch（`heartbeat.rs:245`），**不是**「探测服务器在不在」。

### 9.10 各周期任务的作用与互通约束（2026-11-02 追问补充）

> ⚠️ **勘误**：本节 ③ 修正 §9.9④ 中「且断线时另有更密的包……断线不省电，可能更耗」这句表述。
> 该句原文保留不改（遵守改前留档），以本节 ③ 为准。

**① 3s 心跳的四个作用（不是「探测服务器在不在」）**
1. **保 NAT/UDP 映射**：UDP 无连接，NAT 空闲超时（移动网常见 30s~5min）后下行包被丢弃；
   3s 远小于任何 NAT 超时，非常保守。
2. **维持路由存活**：本地 `Idle::new(Duration::from_secs(10))`（`conn.rs:315`）按
   `last_read >= 10s` 删路由；网关路由过期还会触发 `Disconnect` 进重连
   （`maintain/idle.rs:104-111`、`channel/idle.rs:35`）。
3. **在线状态 / 节点列表同步**：发给网关的心跳带 `device_list` epoch（`heartbeat.rs:245`），
   对端靠收包判断你在线。
4. **P2P 链路探测载体**：发给每条已建路由，以及「在线但无路由」的 peer（经服务端转发）。

**② 各周期任务作用一览（v1.2.9 实证）**

| 任务 | 周期 | 作用 | 离线时发包 |
| --- | --- | --- | --- |
| `heartbeat` | **3s** | 上述四项 | 网关包照发；peer 探测跳过 |
| `idle_gateway` | **5s** | **断线重连**：离线时重新 DNS 解析服务端域名（应对服务端 IP 变化）、发握手包、TCP 模式重建连接（`idle.rs:129-155`） | **是**（唯一新增的） |
| `addr_request` / STUN | **1s**（各 UDP 通道轮转）/ **19s**（最后一通道）/ 对称 NAT 拿到公网端口后 **600s** | 向服务端 AddrRequest / STUN 探测，拿本机公网 IP:端口、判断 NAT 类型，供打洞用 | **否**（`:84-86` 离线 return） |
| `punch` | 在线 **5/6/7s** 抖动 / 离线 5s | P2P 打洞：交换公网地址尝试建直连；抖动避免多端撞车；通道够了就不再打（`punch.rs:253-265`） | **否**（`:179-196` 离线只 sleep） |
| `retrieve_nat_type` | **600s** | 重测 NAT 类型（锥形/对称），应对 WiFi↔LTE 切换 | 独立线程，与在线无关 |
| `client_relay` | **30s** | 探测「借道其它客户端中继」，减少对服务端中继的依赖 | **否**（`heartbeat.rs:172` 离线 return） |
| `idle_route` | 本地 **100ms / 3000ms** | 检查 10s 无数据的路由并删除；**不发网络包**，只耗 CPU 唤醒 | 本地空转 |
| `up_status` | 首 **60s**，之后 **600s** | 上报流量统计、P2P 列表、NAT 类型给服务端（管理/统计用） | **否**（`:68` 离线 return） |

**③ 勘误：断线时到底发不发这些包**
离线时 `addr_request`、`punch`、`client_relay`、`up_status` **都有 offline 检查、不发网络包**；
只有 `idle_gateway` 5s 重连（DNS + 握手）新增发包，心跳网关包照发。
⇒ 准确说法：**断线时发包总量比在线少，但不会因此省电**（3s 心跳照发、5s 重连不停且含 DNS 查询）。
不能靠「让它掉线」来省电。

**④ ★ 互通约束：为什么「心跳不能改」**
- 本地 `Idle` 10s 判死（`conn.rs:315`）+ **对端（存量 1.x 客户端）的 `Idle` 同样是 10s 硬编码**：
  它们靠收到我们的心跳包刷新路由 `last_read`。单方面把 3s 放宽 → 对端 10s 收不到 →
  删路由 → 判定我们离线 → **互通中断**。而服务端不能升级、存量客户端不能改。
- 服务端侧（vnts v1.2.9）无显式客户端心跳超时常量，只有会话缓存 TTL
  （`cipher_session` 120s / `addr_session` 20s / `ip_session` 24h，`core/store/cache.rs:117-127`）。
- ⇒ **「放宽心跳」在「必须互通存量 1.x」前提下基本堵死**，最多 3s→4~5s 的微小调整
  （且丢包容错变差），收益很小。可安全改的只有「本地探测类任务」与「发包相位」，
  因为它们不改变对外协议语义。

> **变更记录（2026-11-02，改后回填）**：原 ④「省电路径分层」、⑥「重编译可做/不可做清单」、
> ⑦「分类勘误 + 用户决策」三段，因用户确立硬约束「**无论什么状态下虚拟网内必须可互访、
> 且其它设备可访问本机，可接受延迟、不接受永远访问不到**」而整体作废，已删除。
> 事实部分（① 心跳四作用 / ② 周期任务表 / ③ 断线勘误 / ④ 互通约束）与 §9.1~§9.9 全部保留；
> 代码里的 `useChannel` 预留字段与 `VntConfigBuilder.kt:56` 透传未动。
> 改前原文逐字留档 + 回滚配方见 `.workbuddy/memory/2026-11-02.md` 末尾「改前留档」段。
