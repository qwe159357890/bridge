# TodoList — 未了结事项累积清单

> - 每轮会话把用户提出的「需要修改 / 新增 / 优化」逐项归纳**追加**到本文件；
> - 后续轮次用户确认某项「已修复 / 完成 / 结束」→ **删除**对应条目；
> - 用户未确认完成的事项**一直累积保留**，作为跨轮次跟踪清单；
> - 每轮回复格式：先「本轮改动总结」（逐项归纳），后「生效提示」表格。

---

## 未了结事项（2026-10-28 14:36 三项功能调研）

1. 【管理后台】侧边栏 tab 收缩/展开功能（收缩态不占位、不影响交互）。✅ 已实现（layout/index.vue，未实测浏览器交互）
2. 【管理后台】新短信实时提醒：全局冒泡 + 声音 + 侧边栏未读角标。✅ 已实现（服务端 data.py + 前端 layout/index.vue，未实测端到端）
3. 【客户端】蓝牙耳机按键监听（最小验证版 MediaSession + Toast）。❌ 已剥离（2026-10-28 18:53 因 Android 焦点竞争无法抢过出声中的其它 App，用户决定移除；经验教训见 docs/keepalive/design.md）

## 打包记录（2026-10-28 14:58）
- 已打包 v0.1.60（API 通道推送 + tag + dispatch 触发 CI，构建成功）。
- APK：releases/app-debug-v0.1.60.apk（18800020 字节，AXML 验证 versionName=0.1.60）。
- 已同步到更新目录 server/data/updates/bridge-v0.1.60.apk（check_update 可提示升级）。

## 打包记录（2026-10-28 15:57）
- 已打包 v0.1.61（API 通道推送 + tag + dispatch 触发 CI，构建成功，job build 全 success）。
- 本轮改动：蓝牙按键 setActive 激活（修复收不到按键）+ 新短信事件改 async 端点（修复事件广播失效）+ 服务端日志落盘 + 三端加日志。
- APK：releases/app-debug-v0.1.61.apk（18800020 字节，AXML 验证 versionName=0.1.61）。
- 已同步到更新目录 server/data/updates/bridge-v0.1.61.apk。

## 修复记录（2026-10-28 16:0x 三项问题）
1. 【角标位置】短信红点角标从右下角改到图标左上角（el-badge 包住图标 + CSS 定位）。✅
2. 【重复通知】新短信重复通知修复：服务端 save_batch 返回 new_messages 精确区分新增，逐条广播 + 60s 内存去重；前端同样加 sms_key 去重（双保险）。✅
3. 【蓝牙按键收不到】根因：Bridge 未请求音频焦点，媒体按键只发给持焦点会话。修复：requestAudioFocus(AUDIOFOCUS_GAIN_TRANSIENT) 瞬时焦点，收到按键后立即 abandon 还焦点。✅ 编译通过，待真机验证。

## 打包记录（2026-10-28 16:15）
- 已打包 v0.1.62（API 通道推送 + tag + dispatch 触发 CI，构建成功 #122）。
- 本轮改动：短信角标左上角 + 重复通知去重（服务端逐条广播+60s去重 / 前端去重）+ 蓝牙按键请求音频焦点。
- APK：releases/app-debug-v0.1.62.apk（18800020 字节，sha256=773fa54f… 与 Release digest 一致，AXML 验证 versionName=0.1.62）。
- 已同步到更新目录 server/data/updates/bridge-v0.1.62.apk。

## 修复记录（2026-10-28 16:22 两项问题）
1. 【短信图标消失+角标不消】根因：el-badge 用 v-if 在未读为 0 时连图标一起移除；角标只在刷新时清零。修：el-badge 始终渲染、用 :hidden 控制角标显隐；加 watch 路由进 /sms 即清零角标。✅
2. 【蓝牙按键无反应+服务端无日志】根因：①按键事件只在本地 Timber（release 不输出）+Toast，完全没上报服务端；②MediaSession 未设 PlaybackState（系统不路由按键）；③焦点请求结果无法远程确认。修：客户端加 PlaybackState(含 PLAY/PAUSE/NEXT/PREV 等 action) + 按键经 WS 上报 media_button 事件；服务端 ws.py 加 _handle_media_button 处理（记录日志+广播）；启动时上报 started 状态（含 has_focus/session_active）便于远程排查。✅ 编译通过，待真机验证。

## 打包记录（2026-10-28 16:39）
- 已打包 v0.1.63（API 通道推送 + tag + dispatch 触发 CI，构建成功 #124）。
- 本轮改动：短信角标图标消失修复 + 选中短信 tab 清零角标 + 蓝牙按键 PlaybackState + media_button 上报链路。
- APK：releases/app-debug-v0.1.63.apk（18800020 字节，sha256=8bd52ba1… 与 Release digest 一致，AXML 验证 versionName=0.1.63）。
- 已同步到更新目录 server/data/updates/bridge-v0.1.63.apk。

## 日志排查 + 时序修复（2026-10-28 16:45）
- 读 server/logs/server.log 发现：服务端 16:43:14 重启、设备 16:43:19 连接、16:43:21 下发 v0.1.63、16:43:42 断开（装新 APK）、16:43:43 重连。**但全程无任何「蓝牙耳机按键上报/started/media_button」日志**。
- 根因定位：`bluetoothHeadsetObserver.start()` 在 `BridgeApplication.onCreate` 里执行，此时 WS 尚未握手（handshakeReady=false），`reportToServer` 里 `sendBusinessMessage` 静默丢弃 → started 状态根本没发出去。
- 修复：`BluetoothHeadsetObserver` 增加监听 `webSocketClient.handshakeReady.collect`，握手完成后补报 started 状态（与 MusicStateReporter 同模式）。✅ 编译通过。
- **待打包 v0.1.64**：装新 APK 后重启服务端，看日志是否有 started（key_code=-1）上报，据此判断 MediaSession 是否注册、焦点是否授予。

## 客户端版本上报 + v0.1.64 打包（2026-10-28 16:51）
- 用户建议：上报时带上客户端版本号，避免怀疑版本问题。已实现：
  - hello 握手消息加 `app_version` 字段（BridgeWebSocketClient 加 appVersionProvider，NetworkModule 注入 BuildConfig.VERSION_NAME）；
  - 蓝牙 media_button 上报加 `app_version`；
  - 服务端 HelloData 加 app_version 字段 + 连接建立日志打印 `version=...`。
- 同时修复蓝牙 started 上报时序（handshakeReady 握手后补报）。
- 已打包 v0.1.64（API 通道推送 + tag + dispatch，构建 #126 success）。
- APK：releases/app-debug-v0.1.64.apk（sha256=e57602d9… 与 digest 一致，AXML versionName=0.1.64）。已同步 server/data/updates/bridge-v0.1.64.apk。
- **下一步验证**：装 v0.1.64 + 重启服务端，日志应出现「连接建立成功（version=0.1.64）」+「蓝牙耳机按键上报：key_code=-1 action=started has_focus=...」。

## 蓝牙按键功能剥离（2026-10-28 18:53）
- 用户决定：因 Android 音频焦点「一对一」竞争，Bridge 无法抢过**正在出声**的其它媒体 App（如 Aurelay），按键监听功能无法达到「无论何时都正常」的目标，故**彻底剥离**该功能。
- 剥离范围（已全部完成）：
  - 客户端：删除 `app/.../media/BluetoothHeadsetObserver.kt`；`BridgeApplication.kt` 移除 `bluetoothHeadsetObserver` 注入与 `start()` 调用。
  - 服务端：`server/app/api/ws.py` 删除 `_handle_media_button()` 函数与 `event == "media_button"` 分支。
  - **保留**（与蓝牙按键无关，勿误删）：保活 `SilentPlayer`（无声播放锚点，受「后台资源占用→无声播放」开关控制）、保活 BLE 扫描（`ResourceHolder`）、版本上报 `app_version`（hello/日志/崩溃通用）。
- 经验教训已沉淀到 `docs/keepalive/design.md` 附录。
- 编译校验：`:app:compileDebugKotlin` BUILD SUCCESSFUL；`ws.py` 语法 OK。
