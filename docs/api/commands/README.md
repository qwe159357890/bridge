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
| `get_lock_state` | 查询锁屏状态 | [control.md#get_lock_state-查询锁屏状态](control.md#get_lock_state-查询锁屏状态) |
| `get_screen_state` | 查询息屏状态 | [control.md#get_screen_state-查询息屏状态](control.md#get_screen_state-查询息屏状态) |
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
| `get_device_stats` | 电/流量统计（电量+活跃时长+流量） | [data.md#get_device_stats-电流量统计](data.md#get_device_stats-电流量统计) |

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
| `vnt_start` | 开始组网 | [system.md#vnt_start-开始组网](system.md#vnt_start-开始组网) |
| `vnt_stop` | 停止组网 | [system.md#vnt_stop-停止组网](system.md#vnt_stop-停止组网) |
| `vnt_status` | 组网状态 | [system.md#vnt_status-组网状态](system.md#vnt_status-组网状态) |
| `ssh_start` | 启动 SSH 服务 | [system.md#ssh_start-启动-ssh-服务](system.md#ssh_start-启动-ssh-服务) |
| `ssh_stop` | 停止 SSH 服务 | [system.md#ssh_stop-停止-ssh-服务](system.md#ssh_stop-停止-ssh-服务) |
| `ssh_status` | SSH 服务状态 | [system.md#ssh_status-ssh-服务状态](system.md#ssh_status-ssh-服务状态) |
| `accessibility_status` | 无障碍状态 | [system.md#accessibility_status-无障碍状态](system.md#accessibility_status-无障碍状态) |
| `accessibility_enable` | 开启无障碍 | [system.md#accessibility_enable-开启无障碍](system.md#accessibility_enable-开启无障碍) |
| `accessibility_disable` | 关闭无障碍 | [system.md#accessibility_disable-关闭无障碍](system.md#accessibility_disable-关闭无障碍) |
| `wechat_release_status` | 微信离开即释放·状态 | [system.md#wechat_release_status-微信离开即释放状态](system.md#wechat_release_status-微信离开即释放状态) |
| `wechat_release_enable` | 微信离开即释放·开 | [system.md#微信离开即释放开关命令v0251-引入](system.md#微信离开即释放开关命令v0251-引入) |
| `wechat_release_disable` | 微信离开即释放·关 | [system.md#微信离开即释放开关命令v0251-引入](system.md#微信离开即释放开关命令v0251-引入) |
| `ui_lock_start` | 开启无界面模式 | [system.md#无界面模式命令v0251-引入儿童锁--防误触](system.md#无界面模式命令v0251-引入儿童锁--防误触) |
| `ui_lock_stop` | 关闭无界面模式 | [system.md#无界面模式命令v0251-引入儿童锁--防误触](system.md#无界面模式命令v0251-引入儿童锁--防误触) |
| `ui_lock_status` | 无界面模式状态 | [system.md#ui_lock_status-无界面模式状态](system.md#ui_lock_status-无界面模式状态) |

## 🖱️ ui（界面自动化）→ [ui.md](ui.md)

> **v0.2.40 起 ui 分类扩展到 66 条命令**。全量命令清单与语义见
> `docs/research/droidline-feature-inventory.md`，使用方式见 `docs/guides/automation.md`。
> 下表仅列出有独立实现文档章节的命令，其余命令以 `—` 标记（语义以 inventory/guides 为准）。

| 命令 | 中文名 | 文档 |
|---|---|---|
| `ui_dump` | 导出界面结构 | [ui.md#ui_dump-导出界面结构](ui.md#ui_dump-导出界面结构) |
| `ui_find` | 查找控件 | [ui.md#ui_find-查找控件](ui.md#ui_find-查找控件) |
| `ui_click` | 点击控件 | [ui.md#ui_click-点击控件](ui.md#ui_click-点击控件) |
| `ui_set_text` | 输入文本 | [ui.md#ui_set_text-输入文本](ui.md#ui_set_text-输入文本) |
| `ui_gesture` | 手势操作 | [ui.md#ui_gesture-手势操作](ui.md#ui_gesture-手势操作) |
| `ui_back` | 返回 | [ui.md#ui_back--ui_home-全局动作](ui.md#ui_back--ui_home-全局动作) |
| `ui_home` | 回桌面 | [ui.md#ui_back--ui_home-全局动作](ui.md#ui_back--ui_home-全局动作) |
| `rpc_start` | 启动 RPC 服务 | [ui.md#python-rpcrpc_start--rpc_stop--rpc_status](ui.md#python-rpcrpc_start--rpc_stop--rpc_status) |
| `rpc_stop` | 停止 RPC 服务 | [ui.md#python-rpcrpc_start--rpc_stop--rpc_status](ui.md#python-rpcrpc_start--rpc_stop--rpc_status) |
| `rpc_status` | RPC 服务状态 | [ui.md#python-rpcrpc_start--rpc_stop--rpc_status](ui.md#python-rpcrpc_start--rpc_stop--rpc_status) |
| `ui_long_click` / `ui_clear` / `ui_sendkey` / `ui_scroll_to` / `ui_wait_idle` / `ui_wait_gone` / `ui_exists` / `ui_get_text` / `ui_checked` / `ui_enabled` / `ui_selected` / `ui_count` / `ui_which` / `ui_in_app` / `ui_keyboard_shown` / `ui_last_toast` / `ui_color` / `ui_recents` / `ui_open_notifications` / `ui_quick_settings` | 元素动作/全局动作 | —（见 inventory/guides） |
| `ui_launch` / `ui_open_url` / `ui_intent` / `ui_apps` / `ui_installed` / `ui_launch_app` / `ui_package_name` / `ui_app_name` / `ui_open_app_setting` | 应用命令 | — |
| `ui_screen_on` / `ui_locked` / `ui_wake` / `ui_orientation` / `ui_info` / `ui_set_brightness` / `ui_get_brightness` / `ui_get_mem` / `ui_is_charging` | 设备命令 | — |
| `ui_find_color` / `ui_find_multi_colors` / `ui_sensors` | 找色/传感器 | — |
| `ui_file_read` / `ui_file_write` / `ui_file_append` / `ui_file_exists` / `ui_file_list` / `ui_file_copy` / `ui_file_move` / `ui_file_remove` | 文件命令 | — |
| `ui_media_seek` / `ui_media_volume` / `ui_media_position` / `ui_media_duration` / `ui_media_state` | 媒体命令 | — |
| `ui_notifications` / `ui_has_notification` / `ui_notification_reply` / `ui_notification_click` / `ui_notification_dismiss` / `ui_clipboard` | 通知/剪贴板 | — |

## 🔋 jikong（极空保护板）→ [jikong.md](jikong.md)

| 命令 | 中文名 | 文档 |
|---|---|---|
| `jikong_scan` | 扫描极空设备 | [jikong.md#jikong_scan-扫描极空设备](jikong.md#jikong_scan-扫描极空设备) |
| `jikong_connect` | 连接极空设备 | [jikong.md#jikong_connect-连接极空设备](jikong.md#jikong_connect-连接极空设备) |
| `jikong_disconnect` | 断开极空设备 | [jikong.md#jikong_disconnect-断开极空设备](jikong.md#jikong_disconnect-断开极空设备) |
| `jikong_status` | 极空状态 | [jikong.md#jikong_status-极空状态](jikong.md#jikong_status-极空状态) |
| `jikong_mos` | 极空 MOS 控制 | [jikong.md#jikong_mos-极空-mos-控制](jikong.md#jikong_mos-极空-mos-控制) |
| `jikong_balance` | 极空均衡控制 | [jikong.md#jikong_balance-极空均衡控制](jikong.md#jikong_balance-极空均衡控制) |
| `jikong_write_param` | 极空写参数 | [jikong.md#jikong_write_param-极空写参数](jikong.md#jikong_write_param-极空写参数) |

---

## 维护规则

1. 分类文件名 = category key；后端新增分类时才允许新增文档文件（文件数天然封顶）。
2. 文档不复制 catalog：只写实现语义（机制/多端流程/副作用/坑），参数细节以 catalog 为准。
3. 命令满足其一才写文档：多端协作流程、客户端副作用（缓存/状态）、有踩坑记录。
4. 新增/落地命令时同步本表；实现文档章节命名 `## 命令名 中文名`。
