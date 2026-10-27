# 命令实现文档索引

> `docs/api/commands/` 下按**命令分类**聚合的实现文档索引（分类文件名 =
> `command_catalog.py` 的 category key，与管理后台侧边栏分类树同一套分类）。
>
> 本表同时是「哪些命令缺文档」的台账：`—` 表示暂无实现文档（简单命令可能永远不需要，
> 见 [../command-catalog.md](../command-catalog.md) 的文档组织规则）。
> 命令清单以 `server/app/core/command_catalog.py` 为单一事实来源，新增命令时同步本表。

## 🎛️ control（控制）→ [control.md](control.md)

| 命令 | 中文名 | 文档 |
|---|---|---|
| `play_music` | 播放音乐 | [control.md#play_music](control.md#play_music-播放音乐) |
| `pause_music` | 暂停音乐 | [control.md#pause_music--resume_music--stop_music](control.md#pause_music--resume_music--stop_music-播放控制) |
| `resume_music` | 继续播放 | [control.md#pause_music--resume_music--stop_music](control.md#pause_music--resume_music--stop_music-播放控制) |
| `stop_music` | 停止音乐 | [control.md#pause_music--resume_music--stop_music](control.md#pause_music--resume_music--stop_music-播放控制) |
| `set_volume` | 调节音量 | [control.md#set_volume-调节音量](control.md#set_volume-调节音量) |
| `get_volume` | 获取音量 | [control.md#get_volume-获取音量](control.md#get_volume-获取音量) |
| `lock_screen` | 锁屏 | [control.md#lock_screen-锁屏](control.md#lock_screen-锁屏) |
| `restart_app` | 重启客户端 | [control.md#restart_app-重启客户端](control.md#restart_app-重启客户端) |
| `send_sms` | 发送短信 | [control.md#send_sms-发送短信](control.md#send_sms-发送短信) |
| `dial_phone` | 拨打电话 | [control.md#dial_phone-拨打电话](control.md#dial_phone-拨打电话) |
| `mark_sms_read` | 标记短信已读/未读 | [control.md#mark_sms_read-标记短信已读未读](control.md#mark_sms_read-标记短信已读未读) |

## 📡 data（数据采集）→ [data.md](data.md)

| 命令 | 中文名 | 文档 |
|---|---|---|
| `get_location` | 获取位置 | [data.md#get_location](data.md#get_location-获取位置) |
| `get_current_page` | 获取当前页面 | [data.md#get_current_page-获取当前页面](data.md#get_current_page-获取当前页面) |
| `get_contacts` | 获取通讯录 | [data.md#get_contacts-获取通讯录](data.md#get_contacts-获取通讯录) |
| `get_sms` | 获取短信 | [data.md#get_sms-获取短信](data.md#get_sms-获取短信) |
| `get_call_log` | 获取通话记录 | [data.md#get_call_log-获取通话记录](data.md#get_call_log-获取通话记录) |
| `get_sim_info` | 获取SIM卡信息 | [data.md#get_sim_info-获取sim卡信息](data.md#get_sim_info-获取sim卡信息) |
| `get_network` | 获取网络状态 | [data.md#get_network-获取网络状态](data.md#get_network-获取网络状态) |
| `get_battery` | 获取电量 | [data.md#get_battery-获取电量](data.md#get_battery-获取电量) |

## 🎬 media（音视频）→ [media.md](media.md)

| 命令 | 中文名 | 文档 |
|---|---|---|
| `screenshot` | 截屏 | [media.md#screenshot-截屏](media.md#screenshot-截屏) |
| `record_screen` | 录屏 | [media.md#record_screen-录屏](media.md#record_screen-录屏) |
| `screen_live` | 投屏直播 | [media.md#screen_live-投屏直播](media.md#screen_live-投屏直播) |
| `take_photo` | 拍照 | [media.md#take_photo-拍照](media.md#take_photo-拍照) |
| `record_video` | 录像 | [media.md#record_video-录像](media.md#record_video-录像) |
| `record_audio` | 录音 | [media.md#record_audio-录音](media.md#record_audio-录音) |
| `stream_stop` | 停止流式采集 | [media.md#stream_stop-停止流式采集](media.md#stream_stop-停止流式采集) |
| `clear_media` | 清理设备媒体 | [media.md#clear_media-清理设备媒体](media.md#clear_media-清理设备媒体) |

## 💬 interact（交互）→ [interact.md](interact.md)

| 命令 | 中文名 | 文档 |
|---|---|---|
| `show_toast` | 弹提示 | [interact.md#show_toast-弹提示](interact.md#show_toast-弹提示) |
| `speak` | 语音播报 | [interact.md#speak-语音播报](interact.md#speak-语音播报) |
| `vibrate` | 震动 | [interact.md#vibrate-震动](interact.md#vibrate-震动) |
| `show_dialog` | 弹窗 | [interact.md#show_dialog-弹窗](interact.md#show_dialog-弹窗) |
| `send_notification` | 发送通知 | [interact.md#send_notification-发送通知](interact.md#send_notification-发送通知) |

## ⚙️ system（系统）→ [system.md](system.md)

| 命令 | 中文名 | 文档 |
|---|---|---|
| `upload_log` | 上传日志 | [../ws-client.md](../ws-client.md) §五/§七（命令框架 + 日志上传） |
| `check_update` | 检查更新 | [system.md#check_update-检查更新服务器目录版不依赖-github](system.md#check_update-检查更新服务器目录版不依赖-github) |

---

## 维护规则

1. 分类文件名 = category key；后端新增分类时才允许新增文档文件（文件数天然封顶）。
2. 文档不复制 catalog：只写实现语义（机制/多端流程/副作用/坑），参数细节以 catalog 为准。
3. 命令满足其一才写文档：多端协作流程、客户端副作用（缓存/状态）、有踩坑记录。
4. 新增/落地命令时同步本表；实现文档章节命名 `## 命令名 中文名`。
