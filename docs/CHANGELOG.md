# Bridge 变更记录（CHANGELOG）

> 记录版本里程碑与关键修复脉络。细节见对应专题文档，工作日志见 `.workbuddy/memory/`。

---

## 2026-10-30 · 🔌 智能控制（ESP32 继电器）上线里程碑（v0.1.87→v0.1.93）

**节点意义**：调研 `references/`（Vue3 前端 `relay-control` + FastAPI 中继服务）后，将「ESP32 继电器控制」
以**方案 A（客户端主动直连参考中继服务器，服务端零改动）**完整集成进 Bridge，并经历
「初版 → 状态刷新攻坚 → 照参考重写 → 命令确认层」四个阶段收敛。**最新客户端 v0.1.93。**

### 一、功能落地（v0.1.87 初版）

- 主页「发现」Tab →「智能控制」：单设备卡片（电源环光晕 + SVG 线框图标 + 粒子特效）+
  窗格模式（每行两列卡片网格）+ 关闭二次确认 + 服务器设置 + 自动重连。
- 新增 `switch/` 包（`RelaySwitchProtocol/Client/Prefs/State`，与 `relay/` 音频中继严格命名隔离），
  图标用 `PathParser` 1:1 还原参考项目手写 SVG path；连接生命周期为页面级（进入 start、离开 stop）。
- 设置 → 连接页新增「智能控制」分区（地址/API Key/设备/端口），设备 ID 改为下拉选择昵称。

### 二、状态刷新攻坚（v0.1.88→v0.1.91，多轮根因定位）

- v0.1.88：设备下拉、开关后自动拉状态、窗格补粒子特效；针对休眠设备（2~10s）曾加「执行中态 + 轮询」。
- v0.1.89：修复「数据已更新但 UI 不动」的第一层根因（监听只重建 devices 引用，pending/status 不触发重组），
  并加 `DiagLog` 关键节点日志（set/轮询/收到 status/超时），接入「客户端上报日志 + API 查询」远程排障。
- v0.1.90：`key(revision)` 强制重建内容区（对 LazyVerticalGrid 参数传递无效的补丁）。
- v0.1.91：**根治**——状态层改为单一不可变快照 `SwitchUiState` + `mutableStateOf`，卡片显示值全部参数化；
  同时修复服务器地址 `http://` 前缀未归一化（日志 `Unable to resolve host "http"` 根因）、
  窗格模式未订阅全部设备、`device_online` 未补订阅等三处协议偏差。

### 三、照参考项目完全重写（v0.1.92）

- 砍掉自研的「执行中/轮询/离线宽限」三套机制，显示状态严格回归参考的三量推导：
  `connected + device.online + statusCache`（等待连接灰 → 获取中 → 已开启绿/已关闭红徽章）。
- 点击只发 `set` + Toast，纯等设备上报 status 广播；订阅策略逐函数对照 `main.ts`
  （窗格订阅全部、单卡只保留当前；device_online 立即 subscribe+get_status）；
  固定 3 秒重连 + OkHttp ping 保活；窗格点卡片=选中设备切单卡；设置保存整体重连。

### 四、命令确认与超时重试（v0.1.93）

- `set` 成功后按「设备+端口」登记等待命令并禁用对应按钮（防重复点击），收到含该端口的
  真实 status 即解除；同端口新命令以 commandId 覆盖旧命令，旧超时回调自然失效。
- 15 秒未确认自动补发一次 `get_status`，再等 10 秒（覆盖休眠唤醒）；仍无回应提示
  「状态未确认，请重试」，按钮变「重试开启/关闭」，按原目标值重发**绝对 set**（幂等，不误反转）。
- 设备离线、WS 断开、离开页面均清理等待命令；真实开关状态仍只由设备上报驱动。

### 关键经验沉淀

- **Compose 漏刷的根治法是「不可变快照 + 参数化」**，revision/key() 打补丁都是徒劳；
  LazyVerticalGrid 的 items diff 不受 lambda 捕获值变化影响。
- 参考项目服务器地址默认**带协议前缀**，客户端拼接前必须归一化（http/https//host 等）。
- 状态更新的唯一可靠途径是 `subscribe` + 服务端 `_broadcast_device_status` 广播；
  `get_status` 只是转发给设备等回报，不回服务端缓存。
- 继电器 `set` 是绝对状态（0=开、1=关），重试/重发天然幂等；电平反转由服务端统一处理。
- 客户端上报日志（DiagLog）+ 服务端日志 API 是远程排障的关键链路，关键节点必须埋点。

---

## 2026-10-29 · 🔁 音频中继「永远连接」自愈里程碑（v0.1.83）

**节点意义**：解决「手机放音（音频中继）切换网络后无法自动恢复，必须重启中转服务器才能重新出声」的
历史顽疾，确立「用户不点停止就永远连接、永远尝试接收」的核心体验。**最新客户端 v0.1.83。**

### 背景与根因

- 音频中继（手机放音）走的是 **Aurelay 第三方中转服务器**（`039039.xyz:15151`，后端零改动），
  与 Bridge 自建服务端（主 WebSocket、心跳、上报）是**两套完全独立的连接体系**，本次改造**仅动中继体系**。
- 根因三重：① 客户端无网络切换感知（`RelayClient` 未注册 `ConnectivityManager` 监听）；
  ② 半开连接下 OkHttp `onFailure`/`onClosed` 可能永远不触发（`readTimeout=0` + 无心跳超时判死）；
  ③ 停止→重连清不掉服务端旧会话，重新 `hello` 无法重建干净链路。

### 四道自愈防线（只要不点「停止」，就永远在尝试接收）

| 防线 | 触发源 | 速度 | 覆盖场景 |
|---|---|---|---|
| ① 网络监听 | `ConnectivityManager.onLost` | 秒级 | 切换 WiFi↔蜂窝、断网、信号丢失 |
| ② 心跳超时判死 | `lastAliveAt` 超 60s | 分钟级 | 半开连接、漏监听、悄悄断线 |
| ③ OkHttp 回调 | `onFailure`/`onClosed` | 即时 | 服务器断、握手失败（既有） |
| ④ 重连前重新上报 | `onBeforeReconnect` → `RelayReporter.reportNow` | 每次重连 | 清服务端旧会话、让 PC 重新撮合 |

### 改动文件

- `relay/RelayProtocol.kt`：新增 `HEARTBEAT_TIMEOUT_MS = 60_000L` 心跳超时判死阈值。
- `relay/RelayClient.kt`：新增 `onBeforeReconnect` 回调钩子；`pingLoop` 加心跳超时判死（60s 无数据即 `forceReconnect`）；
  `scheduleReconnect` 重连循环每次 `connectOnce()` 前调用 `onBeforeReconnect`。
- `relay/AudioRelayService.kt`：`startRelay` 注入 `onBeforeReconnect`（重新上报）+ 注册网络监听
  （`registerDefaultNetworkCallback`，`onLost` 触发 `forceReconnect`）；`stopRelay` 取消监听、解绑钩子。

### 验证与边界

- **用户实测**：多次切换网络临时验证通过，可自动恢复接收（无需再重启中转服务器）。
- **回退基线**：改造前远程 main HEAD `5364cd2f`（v0.1.82），本次提交 `74b30792`（v0.1.83）。
- **边界（已如实告知）**：客户端可 100% 保证「永远尝试重连」，但最终能否出声仍取决于 Aurelay
  第三方服务器对「同 device_id 断线重连」的处理；第④道重新上报可覆盖绝大多数场景。

---

## 2026-10-29 · 🏁 日志系统重构 + 数据库可靠性治理 + 侧边栏 UI 里程碑（v0.1.82）

**节点意义**：自 10-28「阶段性功能完善总结」以来，围绕「日志可追溯」「数据库可靠性」
「管理后台体验」三大主线连续多轮攻坚（v0.1.76 → v0.1.82），全部落地并经用户实测确认。
**最新客户端 v0.1.82，服务端/管理后台持续演进。**

### 一、日志系统彻底重构（Timber 桥接 → 落盘轮转 → 按行存储 → 去重 → 分页）

- **链路打通**：新增 `DiagLogTree` 桥接，让全项目 50+ 模块的 Timber 日志统一进 DiagLog 环形缓冲，
  release 包也不再「无日志可查」；后台下发 `upload_log` 即可拉到网络层/无障碍/媒体/短信等关键日志。
- **落盘 + 轮转**：DiagLog 从「内存 600 条不落盘」升级为「单文件 1MB × 5 份落盘 + 单线程异步写 + 自动清理」，
  重启/崩溃后历史可追溯；崩溃上报前 `flushToFile()` 尽力刷盘。
- **按行存储重构**：日志从「整块 dump 文本一条」重构为「结构化 LogLine 行（ts/level/tag/message）」，
  支持按级别/模块/关键词过滤 + 分页；MySQL `device_logs` 表唯一索引 `(device_id, ts, line_hash)` + `INSERT IGNORE` 去重。
- **多行堆栈防御性收口**：反编译 Timber 确认「Throwable 完整堆栈用 `\n` 拼进 message」根因，
  客户端 `flattenNewlines` 转义 + `parseFileLine` 解析失败即归并（无条件兜底，非针对单一异常）。
- **服务端持久化**：日志 MySQL 落库（`retention_days=30` 按天清理 + `max_entries_per_device=2000` 兜底），
  分页接口 + 昵称优先 + 日志倒序 + 三路并集（日志表∪注册表∪在线连接）设备列表。

### 二、数据库可靠性治理（三类报错根治）

- **每操作独立连接**：12 个 store 中 8 个（location/contacts/calllog/sim/admin_token/connection_event/media/log）
  统一改为「每操作独立连接」contextmanager 模式，根治 pymysql 单例连接被线程池并发复用导致的
  `Packet sequence number wrong` / `Lost connection` 协议错乱。
- **事件循环阻塞根治**：`logs.py` 端点 MySQL 读写改 `run_in_executor` 转线程池；`add()` 逐行 INSERT 改 `executemany` 批量，
  彻底解决「upload_log 后设备 20 秒断连重连」。
- **设备 token 持久化**：新增 `DeviceTokenStore`（仿 `AdminTokenStore`），设备 token 落库 + 启动预热，
  根治「服务端重启后内存注册表清空 → 客户端 401」；客户端配套「4004 自动重注册」兜底（双保险）。

### 三、蓝牙按键监听恢复（零额外功耗版）

- 复用常驻 `KeepAliveService` 语境，创建 MediaSession + 申请一次 AUDIOFOCUS_GAIN，**零定时巡检/额外功耗**；
  目标收窄为「其它 App 不出声时能收到按键」，焦点回调被动重抢；Toast 单实例复用、后台展示改全局弹窗。
- 音频中继「播放几秒就崩」根治：真实根因是 `startForegroundService` 未同步 `startForeground()`
  （前台化断链），非 AudioTrack 竞态。

### 四、管理后台侧边栏 UI 重构

- 收缩态：宽度自适应（图标 24px + 左右对称留白）、图标正方形撑满、菜单项高度/间距收紧、外壁 2px 空隙；
- 展开态：宽度**按最长菜单项文字自动测量**（JS 动态计算，非固定 220px）、内容靠左、右侧贴合；
- 修复多个 Element Plus 菜单高优先级样式覆盖导致的「左侧大空白」「图标盒内偏右」等错位问题。

### 关键工程教训沉淀

- 依赖唯一索引做去重，建索引失败**必须显式告警**，绝不能 `except: pass` 静默吞（否则功能静默失效）。
- `startForegroundService` 必须在 onStartCommand 内尽快 `startForeground()`，绝不能只 notify。
- 任何走 MySQL 的端点：async def 就要 `run_in_executor`，否则改同步 def（反复栽的坑）。
- 有真机必抓 `adb logcat`，别靠猜 native 崩溃。
- Element Plus 菜单收缩态宽度写死 64px、非收缩态 `padding-left` 高优先级，自定义样式需针对性覆盖。

---

## 2026-10-28 · 音频中继（集成 Aurelay 中继模式）✅ 功能落地

**节点意义**：调研 Aurelay 开源源码后，将其「中继模式」（手机放音）合理精简并
集成进 Bridge，作为主页「音频中继」页 + 设置「连接 → 音频中继」分区。**后端零改动**
——复用 Aurelay 中心服务器（`039039.xyz:15151`），Bridge 与 Aurelay 原版可同时接入。

### 集成范围（只保留中继模式）

| 保留 | 砍掉 |
|---|---|
| 中继内核：`RelayProtocol`（ARLY 帧）+ `RelayClient`（OkHttp WS 出站）+ `OpusDecoder`（统一 CSD 83 字节） | 电脑放音（OpusEncoder / AudioCaptureService / DeviceStore） |
| AURL 流解析 + AudioTrack 播放（含 stop 不改 state、会话代次、魔数扫描坑） | 局域网直连 / TLS / auto 链路切换 |
| 24 柱波形 + 中继音量（AudioTrack 软增益，不改系统音量） | 广告内购（AdsManager / BillingManager） |

### 文件落点

| 文件 | 说明 |
|---|---|
| `app/.../relay/RelayProtocol.kt` | ARLY 帧协议（与 Aurelay 逐字节一致） |
| `app/.../relay/RelayClient.kt` | 中转出站 WS（JSON hello 握手 + 二进制音频帧 + 退避重连） |
| `app/.../relay/OpusDecoder.kt` | 系统 Opus 解码（统一 CSD 防永久静音） |
| `app/.../relay/AudioRelayService.kt` | mediaPlayback 前台服务（AURL 解析 + AudioTrack + 24 柱波形） |
| `app/.../relay/RelayPrefs.kt` | 配置持久化（服务器 / 自动重连 / 音量 / token） |
| `app/.../ui/RelayPages.kt` | 主页「音频中继」页（状态卡 + 波形 + 音量 + 启停） |
| `app/.../ui/SettingsPages.kt` | 连接页新增「音频中继」分区（服务器编辑 + 自动重连二次确认） |
| `app/.../ui/MainScreen.kt` | 主页「通讯录」tab →「音频中继」 |

### 关键技术决策

1. **后端零改动**：复用 Aurelay 中转服务器 `039039.xyz:15151` + `/api/aurelay/relay` 路径，
   握手发 JSON `hello`（device_id/token/role），与原版完全兼容。
2. **复用 Bridge 已有能力**：`core-keepalive` 的 SilentPlayer（播放时让位）、OkHttp 4.12.0、
   DiagLog 诊断日志、DataStore device_id（ANDROID_ID）。
3. **交互自服务**：客户端本地自服务（用户手动开始/停止），非管理后台下发。
4. **音量语义**：中继音量 = `AudioTrack.setVolume(0~1)` 软增益，仅调中继播放流，不碰系统音量。
5. **自动重连二次确认**：默认开，关闭时复用核心项确认样式（⚠️ + 红色「确认关闭」）。

---

## 2026-10-28 · v0.1.69 ✅ 蓝牙耳机按键监听功能彻底剥离（里程碑：功能放弃 + 经验沉淀）

**节点意义**：蓝牙耳机按键监听功能历经多轮实现与排障（v0.1.60→v0.1.68）后，
确认目标在 Android 系统机制下**物理上不可达成**，最终彻底剥离。这是一次
「技术方案验证 → 触及系统硬边界 → 果断止损 + 经验沉淀」的完整闭环，值得记录
为工程决策里程碑。

### 剥离原因（核心结论）

> Android 蓝牙媒体按键（AVRCP）是**一对一的音频焦点竞争**：耳机按键永远只发给
> 「当前持有音频焦点」的那个 App。当另一个媒体 App（如 Aurelay）正在**出声播放**
> 时，它持有焦点，`requestAudioFocus(AUDIOFOCUS_GAIN)` 会被系统**拒绝或延迟**
> （返回 `FAILED` / `DELAYED`），系统不允许一个 App 强行打断正在出声的 App。
>
> 因此「无论何时 Bridge 都拿得到按键」这一目标，在系统级焦点仲裁规则下无法达成
> ——这是平台硬限制，不是代码缺陷。

### 剥离清单

| 层 | 改动 |
|---|---|
| 客户端 | 删除 `BluetoothHeadsetObserver.kt`（整文件）；`BridgeApplication.kt` 移除注入 + `start()` |
| 服务端 | `ws.py` 删除 `_handle_media_button()` + `event=="media_button"` 分支 |
| 文档 | `docs/keepalive/design.md` 新增附录沉淀经验；`TodoList.md` 标记已剥离 |

### 明确保留（与蓝牙按键无关，未触碰）

- **`SilentPlayer`**（保活「无声播放」锚点，受「后台资源占用·细化」开关控制，默认开）
- **`ResourceHolder`** 中的 BLE 扫描（保活资源锚点）
- **`app_version`** 版本上报（hello 握手 / 日志 / 崩溃上报通用机制）

### 经验教训（详见 `docs/keepalive/design.md` 附录）

1. 收到媒体按键的三条件缺一不可：`MediaSession.setActive(true)` + 持久音频焦点
   （`AUDIOFOCUS_GAIN`，瞬时焦点会被静默收回）+ `PlaybackState.setState(STATE_PLAYING)`
   （只 `setActions` 不 `setState` 会停在 STATE_NONE，系统不路由按键）。
2. 保活的「无声播放」锚点（`SilentPlayer`）**不申请 AudioFocus**，与「抢焦点」
   是两套独立机制，不能混用；「抢过正在出声的 App」必须让 MediaSession 真正占用
   音频输出，单靠 requestAudioFocus 抢不过。
3. 排查神器：`adb shell dumpsys media_session`（看 `Media button session` 指向）+
   `adb shell input keyevent KEYCODE_MEDIA_PLAY_PAUSE` 模拟按键。

---

## 2026-10-28 · 🏁 阶段性功能完善总结（三端功能清单）

**节点意义**：自 v0.1.55 流式链路稳定里程碑以来，连续多轮 UI/UX 与数据链路打磨
（v0.1.55→v0.1.59 + 多轮未打 tag 改进）全部落地并经用户实测确认，三端功能形态
达到一个小阶段完备状态。**最新客户端 v0.1.59（此后 APK 零改动），服务端/管理后台
持续演进。**

### 客户端 APK（v0.1.59）

- **连接与保活**：WS 长连接 + 自研轻量保活（前台服务 > 电池白名单 > 开机自启 >
  双兜底）；「后台资源占用·细化」弹窗开关实时生效
- **命令执行**：全量命令目录（截屏/录屏/拍照/录像/录音/音量/SIM/网络/位置/应用/
  文件/媒体控制等），见 `docs/api/commands/`
- **实时流**：投屏直播（FLV）+ 流式录像/录音；二进制 WS 帧（省 33% 上行）；
  画质档位 hd/smooth；低延迟模式；CBR 码率（失控根治）；静止时重发上一帧；
  不限时采集（duration=0）
- **短信**：**聚合模式默认开启**（服务端存档所有设备，会话列表+聊天气泡详情+
  游标分页，本机标绿）；「聚合/本机」切换；已读 Bridge 内部映射（免默认身份）；
  默认短信应用一键切换
- **主动上报**：位置周期上报（WorkManager 兜底）+ 新短信即推（ContentObserver）
- **体验**：返回键 Toast 美化；应用内自动更新（走服务器更新目录）

### 服务端 server（Python FastAPI）

- **命令通道**：WS 下发/回执，文本与二进制帧双兼容（向后兼容旧 APK）
- **流转发**：StreamHub 实时转发（不缓存）→ `/api/admin/stream/{device_id}` 二进制
  直播流，mpegts.js 直播源
- **MySQL 持久化**：短信（累积去重+跨设备聚合）、通话记录（覆盖快照）、通讯录
  （覆盖快照）、位置、**上下线记录**（`retention_days` 默认 90 天自动清理，
  MySQL 不可用自动降级内存）
- **设备认证端点**：`/api/device/sms_threads`、`/api/device/sms_messages`（APK 聚合）
- **配套**：更新目录扫描（check_update 不依赖 GitHub）、媒体文件自动清理
  （默认 100 条/7 天）、音乐共享目录、命令目录 catalog 单一权威源

### 管理后台 admin（Vue3 + Element Plus）

- **九大页面**：仪表盘 / 设备管理 / 连接监控 / 命令下发 / 短信 / 通话记录 /
  通讯录 / 媒体文件 / 消息推送（+日志查看）
- **命令下发**：分类+命令卡片按浏览器记忆（localStorage）；参数下发成功后持久化
  记忆并自动回填；目标设备全局 store 跨页面保持；**停止采集四入口同流程**
  （⏹ 按钮 / 切卡 / 切页 / 播放中点 ✕，均 stream_stop+断 WS+15min 封锁）
- **实时画面卡片**：追帧策略（默认/低延迟双档）、画中画 Document PiP（窗口自由
  调大小+内容铺满跟随缩放）、全屏铺满、自绘音量控件、tooltip 美化
- **数据页**：短信聚合模式（全部设备+来源列+按行分组标记/删除）；通话记录/通讯录
  「🔄 从设备刷新」（直发命令+轮询回执+自动重查）；已读/未读双向标记
- **体验**：tab 按浏览器记忆自动跳回；侧边栏导航稳定；深色风格统一

### 关键工程教训沉淀

- 往 PiP/iframe 搬 DOM 后覆盖 scoped 样式必须 `!important`（[data-v] 特异性更高）
- 路由守卫做「首跳恢复」时 redirect 先于守卫解析；默认页记忆恢复必须收窄到根路径首跳
- MySQL 连接禁止跨线程共享（FastAPI 线程池并发 → 协议错乱），高频端点逐操作独立连接
- 功能按钮别用 `v-if` 静默隐藏（置灰+说明）；给用户的说明文案别只放原生 title
- 暴露组件方法前核对 `defineExpose` 清单（路由守卫里调用未暴露方法 = 导航中止）

---

## 2026-10-28 · v0.1.59 ✅ 保活细化弹窗开关实时刷新

- **APK**：「后台资源占用 · 细化」弹窗开关点击后**立即切换**（此前要点开关写盘
  不触发重组、重开弹窗才刷新——组合期直读 SharedPreferences 无重组订阅，改为
  `mutableStateMapOf` 可观察状态；核心项「二次确认关闭」路径经 refreshKey 联动）。

---

## 2026-10-28 · v0.1.58 ✅ APK 短信聚合模式 + 管理后台 UI 六项修复

- **APK**：短信页**聚合模式**（默认聚合）——显示服务端存档里**所有设备**的短信
  （会话键=设备+号码），本机会话标绿「本机」；聚合详情聊天气泡+「加载更早」游标分页；
  顶部「聚合/本机」切换条，本机视图保留原有能力；聚合存档只读。
  服务端配套新增设备认证端点 `GET /api/device/sms_threads`、`/api/device/sms_messages`。
- **APK**：返回键「再按一次退出」Toast 美化（品牌蓝图标圆徽+深蓝渐变胶囊+投影）。
- **admin 六项**：①切命令卡片即销毁实时画面控件（断 WS+移除，自动预连不弹回）
  ②全屏画面铺满（根因 `.stream-video` max-height 420px 未在全屏态解除）
  ③画中画 Document PiP（含音量控件整体搬入，窗口自由调大小）④停止采集按钮
  仅采集中可用 ⑤tooltip 美化 ⑥**侧边栏导航卡死修复**（hide 未进 defineExpose →
  路由守卫 TypeError 导航中止；Playwright 复现实证）。
- **admin**：短信页聚合模式（全部设备+来源设备列+按行设备分组标记/删除）。
- **server**：短信 `list_sms` device_id 改可选；**上下线记录 MySQL 持久化**
  （`connection_events` 表，`retention_days` 默认 90，MySQL 不可用自动降级内存）。

---

## 2026-10-28 · v0.1.56~v0.1.57 ✅ 码率失控闭环 + 短信已读方案落定

- **v0.1.56**：投屏码率失控根治（显式 `KEY_BITRATE_MODE=CBR`，VBR 下 KEY_BIT_RATE 只是
  均值导致实测 22Mbps）；编码器 start 后 request-sync 立即出首帧；二进制 WS 帧
  （省 33% 上行）+ 画质档位 + GOP 1s + 低延迟模式；流式不限时 + 停止采集；回执上传开关。
- **v0.1.57**：短信已读 **Bridge 内部映射**方案落定（ColorOS 拒绝第三方写系统短信库，
  三级回退实测全 0 行 → `SmsReadStore` 本地 JSON 展示层叠加，免默认短信身份）；
  停止采集即断 WS + 手动停止 15min 封锁；音量控件；tooltip 限宽换行。
- v0.1.55 遗留待办「码率失控」「启动黑屏」均已闭环（CBR + request-sync 首帧）。

---

## 2026-10-27 · v0.1.55 ✅ 流式实时链路稳定里程碑（投屏/流式录像不卡顿）

**节点意义**：投屏直播与流式录像的实时播放终于稳定不卡顿。这是自流式功能上线以来
持续多轮排障（TS 转封装 → FLV 对齐器 → 追帧策略 → 静止断流）的收敛节点。

### 本轮关键修复

| 问题 | 根因 | 修复 |
|---|---|---|
| 流式录像只出首帧后停滞 | Android MediaMuxer 的 MPEG-2 TS 时间戳缺陷（PCR/boottime、dts 非单调）→ mpegts.js 解码停滞 | 服务端 `ts_to_flv.py` 统一 TS→FLV 转封装（PTS/DTS 归一化） |
| 卡住→刷新恢复→再卡住死循环 | 512KB 盲缓存任意字节截断/中途加入者字节断层 → `Invalid PrevTagSize` | `flv_aligner.py`：流头+关键帧对齐接入；StreamHub 订阅原子化 |
| 投屏首次无实时画面 | 首次下发时 videoEl 未渲染，attachMediaElement 抛异常被吞 | nextTick + 会话守卫 + 三重预连保险 |
| 追帧跳变（每隔几秒卡一下） | 落后 2.5s 猛 seek = 冻结攒够→瞬跳循环 | 平滑变速追帧 v3：缓冲垫 1.2~3.5s |
| 屏幕静止时周期性卡顿 | ① 上游静止断流十几秒→数据爆发→硬跳；② 解码帧不增被误判卡死→4s 重建循环 | 冻结即时追尾 + 停滞检测区分「上游无帧」+ 重建 12s 冷却 |
| 静止时浏览器旋转 spinner 误导「加载中」 | Chrome 原生 controls 在 waiting 态显示 spinner | video 去掉原生 controls + 卡片头部全屏按钮 |
| **静止期上游断流（治本）** | 设备 Surface 无新帧时编码器不出帧，时间轴断流 | APK：`KEY_REPEAT_PREVIOUS_FRAME_AFTER` 500ms 重复帧（静止期重编上一帧，时间轴连续） |

### 待办（遗留）

- 投屏编码器码率失控（实测 ≈22Mbps vs 配置 2.5Mbps）：静止期重复帧已解决静止断流，
  活动画面的码率爆发（KEY_BIT_RATE 不生效？）仍需单独排查。
- 启动黑屏 ~14s（下发→出画）：设备端编码器启动 + 关键帧对齐等待，属 APK 侧优化项。

---

## 2026-10-27 · v0.1.53

- 投屏直播 FLV sequence header 剥起始码（mpegts FormatError 根因）、首帧不再丢弃、TS 播放追帧自愈。
- 持久镜像幕布稳定复用；宽高清零运算优先级 bug 修复（v0.1.52）。

## 2026-10-27 · v0.1.50~v0.1.51

- 投屏直播（screen_live）上线：MediaCodec + 自研 FlvMuxer，FLV 复用流式通道。
- 音量控制（set_volume 5 流 + get_volume）、流式录像/录音（stream=true + stream_stop）。

## 2026-10-26 · v0.1.41~v0.1.45

- 持久镜像幕布（解决 Android 14+ 投屏令牌一次性）。
- 主动上报默认开（位置周期 + 新短信即推）；媒体文件自动清理。
