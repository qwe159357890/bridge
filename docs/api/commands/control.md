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

---

## 未收录命令

`set_volume`、`lock_screen`、`reboot` 尚未实现/落地，实现时在本文补
`## 命令名` 章节（写实现语义，参数细节以 catalog 为准）。
