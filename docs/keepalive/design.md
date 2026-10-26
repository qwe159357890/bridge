# 保活方案设计

> 本文档归纳、总结两个参考项目的保活方式，作为 Bridge 保活模块（`core/core-keepalive`）的设计依据。

## 一、两个参考项目

| 项目 | 路线 | 核心手段 |
|---|---|---|
| Aurelay（声音中继） | 自研多层（12 层） | 常驻前台服务解耦 + 9 类资源锚点 + 一像素 + 无声播放 + 双兜底 |
| SmsForwarder（短信转发器） | 轻量框架 | 前台服务 + 电池白名单引导 + Cactus 框架 |

## 二、借鉴结论

- 吸收 Aurelay：前台服务与业务**解耦**、资源锚点、一像素、无声播放、Alarm+Job 双兜底、`START_STICKY`。
- 吸收 SmsForwarder：**电池优化白名单主动引导**（最有效）、开机自启、**在最近任务列表中隐藏**（防误杀）。
- 取舍：去掉 Aurelay 的「全量权限申请」激进做法；不引入第三方 Cactus 依赖，改自研。

## 三、最终方案（自研轻量模块）

| 层 | 组件 | 默认开关 |
|---|---|---|
| 常驻前台服务 | `KeepAliveService`（START_STICKY + HIGH 通知） | 开 |
| 资源锚点 | `ResourceHolder`（WakeLock/WifiLock/位置/传感器/蓝牙/相机） | 开 |
| 一像素锚点 | `OnePixelOverlay`（alpha 0.01 悬浮窗） | **关** |
| 无声播放 | `SilentPlayer`（±1 LSB 近静音） | 开 |
| 开机自启 | `BootReceiver` | 开 |
| 双兜底 | `KeepAliveReceiver`（Alarm）+ `KeepAliveJobService`（Job） | 开 |
| 电池白名单 | `KeepAliveUtils` 引导 | 用户手动 |
| 最近任务隐藏 | `KeepAliveUtils.setExcludeFromRecents`（MainActivity onCreate 按偏好应用 + 设置页开关即时生效） | 开 |

**最近任务隐藏细节**（v0.1.39，借鉴 SmsForwarder `enableExcludeFromRecents`）：把本应用任务
标记为 `excludeFromRecents`，最近任务列表不再出现 Bridge 卡片，上滑移除 / 一键清理都碰不到。
与原版差异：SmsForwarder 关开关只存偏好、需重启才恢复显示，Bridge 在开关切换时同步调
`setExcludeFromRecents(false)` 即时恢复。副作用：开启后用户只能从桌面图标进入 Bridge、
无法在最近任务里上滑杀进程（既是目的也是代价），关闭开关即恢复。

## 四、关键踩坑（务必遵守）

1. 前台通知必须 `startForeground` 而非 `notify`。
2. 通知渠道 `IMPORTANCE_HIGH`。
3. 一像素 alpha 必须 0.01 不能 0。
4. 无声播放只 pause+flush，绝不 stop；独立 AudioTrack；帧最低位 ±1 抖动。
5. Android 15+ 开机拉起前台服务受限需 try-catch。
6. 电池白名单引导是最有效手段。

## 五、代码位置

`core/core-keepalive/src/main/java/my/bridge/com/keepalive/`，共 9 个类。
