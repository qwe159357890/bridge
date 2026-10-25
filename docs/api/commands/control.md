# 控制类命令实现（control）

> 命令目录「控制」类（category key: `control`）的**实现文档**。
> 命令元数据（参数 schema / 中文名 / 说明）的唯一权威是
> `server/app/core/command_catalog.py`，本文只写 catalog 说不清的**实现语义**：
> 机制、多端协作流程、客户端副作用（缓存/状态）、踩坑。
> 文档组织规则见 [../command-catalog.md](../command-catalog.md)，命令级索引见 [README.md](README.md)。

---

## play_music 播放音乐

### 音源三形态（url 参数一参三态）

| 形态 | 示例 | 行为 |
|---|---|---|
| ① 远程 URL | `https://example.com/a.mp3` | MediaPlayer 直接流式播放（不缓存） |
| ② 本地绝对路径 | `/storage/emulated/0/Music/a.mp3` | 文件存在即直接播放；不存在回执失败 |
| ③ 裸文件名 | `test.mp3` | 先查**客户端缓存目录**：命中直接播放；未命中请求**服务器共享音乐目录**下载并持久化到缓存目录后播放（下次直接命中） |

判定规则（客户端 `CommandModule.resolveSource`）：

```
http:// 或 https:// 开头 → ①
/ 开头                  → ②
其余                    → ③
```

### 服务器共享音乐目录

- **配置**：`config.yaml` 的 `music.dir`（默认 `data/music`，相对路径相对 **server 根目录**，
  绝对路径原样使用）。可用环境变量 `BRIDGE_MUSIC_DIR` 覆盖。
- **使用**：管理员把音乐文件（mp3/m4a/aac/wav/ogg/flac/opus/mid/amr）放进该目录，
  即可用裸文件名下发播放。
- **安全**：下载接口仅允许**单级纯文件名**（拒绝 `/`、`\`、`..` 路径穿越），
  且扩展名必须在音乐白名单内（目录里混入其他文件不会外发）。

### 下载接口

`GET /api/music/{filename}`（`server/app/api/v1/music.py`）

- **鉴权**：`auth.enabled=true` 时要求设备头 `X-Device-Id` + `X-Device-Token`，
  与 WS hello 握手共用同一套 `registry.authenticate`；鉴权关闭时放行（便于联调）。
- **响应**：200 音频文件流（FileResponse，Content-Type 按 mime 推断）；
  404 服务器目录无此文件；400 文件名/格式非法；401 鉴权失败。
- 单测：`server/tests/test_music.py`（鉴权/穿越/白名单/404/命中）。

### 客户端缓存

- **目录**：`context.getExternalFilesDir("music")`（App 私有外部目录，回退
  `filesDir/music`）。**无需任何存储权限**，卸载 App 自动清理。
- **写入**：先写 `xxx.mp3.tmp` 再原子重命名，避免半截文件被当成有效缓存。
- **命中**：缓存目录存在同名文件即直接播放，不再请求服务器。

### 播放器

- 客户端 `app/src/main/java/my/bridge/com/media/MusicPlayer.kt`（进程内单例 object）。
- 系统 `MediaPlayer`，不引 ExoPlayer/Media3（需求只是「播起来」，依赖最小化）。
- `play()` 阻塞至 prepare 完成，**必须后台线程调用**（命令执行器跑在 Dispatchers.IO）。
- 重复下发 = 切歌（顶掉上一次）；`loop` 参数映射 `MediaPlayer.isLooping`。
- 与保活模块**不耦合**：播放是业务行为，保活是进程行为，职责分离。

### 首次落地改动清单（2026-10-25）

| 端 | 文件 | 改动 |
|---|---|---|
| 服务端 | `app/config.py` + `config.yaml` | 新增 `music.dir` 配置 |
| 服务端 | `app/api/v1/music.py`（新增） | `GET /api/music/{filename}` 下载接口 |
| 服务端 | `app/api/v1/router.py` | 挂载 music 路由 |
| 服务端 | `tests/test_music.py`（新增） | 6 个单测 |
| 客户端 | `core/core-network/.../Command.kt` | `PLAY_MUSIC` 常量 |
| 客户端 | `core/core-network/.../BridgeApi.kt` / `BridgeApiClient.kt` | `downloadMusic()`（设备鉴权头） |
| 客户端 | `app/.../media/MusicPlayer.kt`（新增） | MediaPlayer 封装 |
| 客户端 | `app/.../di/CommandModule.kt` | 注册 play_music 执行器（三路解析 + 缓存） |
| 管理后台 | 无改动 | 参数面板从 catalog 动态渲染，自动生效 |

### 使用示例

1. 服务器上：把 `test.mp3` 放进 `server/data/music/`。
2. 管理后台：命令下发 → 控制 → 播放音乐 → 音源填 `test.mp3` → 下发。
3. 客户端：首次缓存未命中 → 下载 → 缓存到 App 私有 music/ → 播放；
   再次下发 `test.mp3` → 直接命中缓存播放。

---

## pause_music / resume_music / stop_music 播放控制

三个控制命令共用 `MusicPlayer`（`app/.../media/MusicPlayer.kt`）里的同一份播放状态机，
行为约束在客户端按状态判定，非法操作**回执失败**并说明原因（管理后台下发历史可见）：

| 命令 | 空闲 | 播放中 | 暂停中 |
|---|---|---|---|
| `pause_music` | ✗「当前没有正在播放的音乐」 | 暂停（记住进度） | 幂等成功「已处于暂停状态：\<曲目\>」 |
| `resume_music` | ✗「当前没有可恢复的音乐」 | ✗「正在播放中，无需恢复」 | 从暂停进度恢复 |
| `stop_music` | ✗「当前没有正在播放的音乐」 | 停止并释放 | 停止并释放（清掉暂停态） |

与 `play_music` 的交互：

- 播放中下发 `play_music` = **切歌**（顶掉当前播放器，释放后新起）；
- 暂停中下发 `play_music` = 丢弃暂停会话，新起播放（**不**从进度续播，续播用 `resume_music`）。

实现要点：

- `MusicPlayer` 进程内单例，状态机 `IDLE / PLAYING / PAUSED`，记录 `currentSource`
  用于回执展示（如「已暂停：test.mp3」）；状态只存内存，进程被杀即回空闲。
- 非循环曲目自然播完 → `OnCompletionListener` 释放播放器，状态自动回落空闲
  （此后 pause/stop/resume 均回执失败，符合「空闲不可控」约束）。
- `loop=true` 时永远不触发 completion，**必须用 `stop_music` 终止**。
- 控制命令与 `play_music` 可能在不同线程并发执行：`MusicPlayer` 内部全部
  `synchronized`；状态判定与操作之间的窄窗口竞态由播放器的
  `IllegalStateException` 兜底（`CommandModule.executeMusicControl` 统一转失败回执）。
- 回执即状态显示（方案 A）：每次 play/pause/resume/stop 的回执消息都带
  当前状态与曲目，管理后台下发历史天然可见，无需额外上报通道。

### 播放状态上报与后台联动（2026-10-25 第二轮）

管理员不该「发一枪才知道死活」：控制类命令在后台**事前**按设备状态联动禁用，
状态徽标常驻显示。三端数据流（全部复用现有通道，无新依赖）：

```
客户端 MusicPlayer 状态机
  --状态变化--> WS message 通道上报 {"event":"music_state","state":"playing|paused|idle","source":"test.mp3"}
  --> 服务端 ws.py 解析 → MusicStateStore（内存，device_id 维度）
  --> GET /api/devices/{id}/music-state（管理鉴权，选中设备即拉取）
  --> admin 事件总线广播 music_state 事件 → 管理后台 WS 实时更新徽标
```

- **客户端**（`MusicStateReporter`，BridgeApplication 启动时装载）：
  状态一变即上报（播/暂停/恢复/停止/自然播完/出错）；WS 重连成功补报当前状态
  （服务端重启/断连后状态会丢，借此对齐）；未连接时静默丢弃，不做重试队列。
- **服务端**（`core/music_state_store.py` + `api/ws.py`）：
  合法状态 playing/paused/idle，非法忽略；**断连即清除**（此后查询返回 unknown
  并广播 unknown 事件），避免陈旧状态误导。
- **管理后台**（命令下发页）：
  - 选中目标设备即拉取状态并常驻徽标（▶ 播放中：曲目 / ⏸ 已暂停 / 空闲 / 状态未知），
    旁有手动**刷新按钮**（设备重启等场景兜底）；
  - 实时：admin WS 收到 `music_state` 事件即更新当前目标设备的徽标；
  - **按钮联动矩阵**（卡片置灰 + 下发按钮禁用 + 原因提示）：

| 命令 | playing | paused | idle | unknown/离线 |
|---|---|---|---|---|
| `play_music` | ✅（切歌） | ✅ | ✅ | ❌ |
| `pause_music` | ✅ | ❌ | ❌ | ❌ |
| `resume_music` | ❌ | ✅ | ❌ | ❌ |
| `stop_music` | ✅ | ✅ | ❌ | ❌ |

  - **强制下发开关**（下发按钮旁，默认关）：状态显示依赖「上报→存储→SSE→徽标」长链路，
    任一环异常都可能让后台状态 ≠ 设备真实状态，此时按钮被禁会形成**死锁**（如实际在放
    `loop=true` 音乐、后台显示空闲 → 停止按钮永远点不了）。开启后绕过上述矩阵
    （卡片不再置灰、按钮可点），仅要求设备在线；客户端侧真实状态校验**保留**，
    状态不匹配会回执失败原因——即回退到 v0.1.19 之前的「下发后看回执」行为。
    切换目标设备时开关自动重置为关；「未知」状态的提示会引导开启。

  注意：客户端侧的状态约束**仍然保留**（服务端不因前端禁用而放松校验）——
  前端禁用只是体验优化，真实约束以客户端回执为准（防绕过/竞态）。
  单测：`server/tests/test_music_state.py`（store 单元 + ws 处理逻辑 + REST 查询）。
  已知限制：TestClient 的 WS 会话在当前 starlette 版本有兼容性问题，
  ws 处理逻辑以提取的协程直测覆盖，端到端链路待真机验证。

---

## send_sms 发送短信

客户端用**本机 SIM 卡**真实发送短信（会计费），实现核心
`app/.../data/SmsSender.kt`，借鉴 SmsForwarder `PhoneUtils.sendSms` +
autoxClient `sms.js`，并补上两者缺失的**发送结果确认**。

### 发送流程

```
下发 params {phones, content}
→ 客户端校验 SEND_SMS 运行时权限
→ 拆分号码（中英文逗号/分号/竖线均可，去空去重，上限 20 个）
→ 逐号码串行发送：
    divideMessage 切分（>70 字走 sendMultipartTextMessage）
    挂 sent PendingIntent + 动态 BroadcastReceiver
    await 系统 sent 回执（15 秒超时）
→ 汇总逐号码明细回执（任一成功即命令成功，全部失败回执失败）
```

### 关键实现点

- **指定发送卡**（`sim` 参数，v0.1.27+）：`auto` 走系统默认卡；`sim1/sim2/sim3`
  按卡槽序号经 `SubscriptionManager`（READ_PHONE_STATE）解析 subscriptionId，
  `SmsManager.getSmsManagerForSubscriptionId` 指定卡发送；**指定的卡不存在时
  回执失败**（附当前卡列表），不静默退默认卡。回执带实际使用卡
  （如 `用[卡1（138xxx）]发送完成：成功 2/2`）。
- **结果确认**：`sendTextMessage` 同步不抛异常 ≠ 发送成功（无 SIM/射频关闭
  是异步 RESULT_ERROR）。每个号码的 sent PendingIntent 配对
  `RECEIVER_NOT_EXPORTED` 动态接收器（intent `setPackage` 自己），等待
  `RESULT_OK / RESULT_ERROR_NO_SERVICE / RADIO_OFF / NULL_PDU / GENERIC_FAILURE`，
  15 秒无回执计「发送超时」。
- **requestCode 计数器**：PendingIntent requestCode 进程内自增唯一，避免多号码
  broadcast 串扰；flag `FLAG_UPDATE_CURRENT | FLAG_IMMUTABLE`。
- **号码前置校验**：`^\+?[0-9]{3,20}$`，不合法号码不发起发送、明细回执注明
  （系统侧只有链路失败才报错，本地先挡掉明显错号）。
- 写操作语义：客户端只机械执行 + 如实回执，下发前确认责任在后台操作者
  （catalog 描述已注明计费风险）。

---

## dial_phone 拨打电话

实现核心 `app/.../data/PhoneDialer.kt`，借鉴 autoxClient `phone.js` 的双模式：

| mode | 行为 | 权限 |
|---|---|---|
| `direct`（默认） | `ACTION_CALL` 直接拨打，跳过拨号盘 | CALL_PHONE（运行时） |
| `dialer` | `ACTION_DIAL` 打开拨号盘并预填号码，设备持有者手动拨出 | 无需权限（天然人工确认层） |

### 指定使用卡（sim 参数，v0.1.27+）

- `direct` 模式：`sim1/sim2/sim3` 按卡槽序号取
  `TelecomManager.callCapablePhoneAccounts[slot]`，经
  `PHONE_ACCOUNT_HANDLE` extra 指定拨号卡（对应关系 AOSP 约定 ≈ 卡槽顺序，
  个别 ROM 不一致时自动退回默认卡并在回执注明）；**指定的卡不存在时回执失败**
  （附当前卡列表），不静默换卡。`auto` / `dialer` 模式选卡由系统/设备上用户决定。
- 回执带实际使用卡描述，如「已用 卡1（138xxx）拨打 138…」。

### 降级策略

`direct` 但 CALL_PHONE 未授权时**自动降级**为 `dialer`，回执注明
「无直接拨打权限，已降级为拨号盘」。CALL_PHONE 已在 App 启动批量权限申请中。

### 已知限制：后台启动 Activity

Android 10+ 限制后台启动 Activity：客户端进程虽为前台服务，息屏/后台时
`startActivity` 可能被系统静默拦截（不抛异常，仅 logcat），**已授予
「显示在其他应用上层」（SYSTEM_ALERT_WINDOW）权限后不受限**。回执文案带
此提示；`dialer` 模式被拦截时拨号盘未打开但回执仍为成功——这是系统限制，
非客户端可检测，真机排障看 logcat `ActivityManager` 拦截日志。

---

## 未收录命令

`set_volume`、`lock_screen`、`reboot` 尚未实现/落地，实现时在本文补
`## 命令名` 章节（写实现语义，参数细节以 catalog 为准）。
