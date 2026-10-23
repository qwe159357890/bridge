# 保活模块集成说明

> 如何把 `core/core-keepalive` 模块集成进 Bridge（或复用指南）。

## 一、模块清单

| 文件 | 职责 |
|---|---|
| `KeepAlivePrefs.kt` | 各层开关配置 |
| `KeepAliveService.kt` | 常驻前台服务（核心） |
| `ResourceHolder.kt` | 资源锚点统一持有/释放 |
| `OnePixelOverlay.kt` | 一像素悬浮窗锚点 |
| `SilentPlayer.kt` | 无声播放锚点 |
| `BootReceiver.kt` | 开机/更新自启 |
| `KeepAliveReceiver.kt` | Alarm 兜底唤醒 |
| `KeepAliveJobService.kt` | Job 兜底唤醒 |
| `KeepAliveUtils.kt` | 电池白名单引导 |

## 二、集成步骤

1. **依赖**：在目标模块 `build.gradle.kts` 添加 `implementation(project(":core:core-keepalive"))`。
2. **Manifest**：合并 `core/core-keepalive/AndroidManifest.template.xml` 中的权限与四大组件声明。
3. **拉起**：在 `Application.onCreate()` 调用 `KeepAliveService.start(this)`。
4. **入口注册**：`KeepAliveEntryActivityHolder.entryActivityClass` 设为自己的 MainActivity。
5. **电池白名单引导**：在首个 Activity 调用 `KeepAliveUtils.requestIgnoreBatteryOptimization(activity)`。
6. **权限申请（关键，易漏）**：在首个 Activity 批量申请保活用到的危险权限，见下节。

## 三、权限申请（务必不要漏）

> ⚠️ **历史教训**：第一版集成时只借鉴了 Aurelay 的分层架构和资源持有逻辑，**漏掉了权限申请入口**，导致：前台服务通知被系统静默拦截（通知栏看不到）、资源锚点空转（代码只检测权限、从不发起申请）。用户反馈「安装后没有任何权限弹窗、手动都找不到通知权限」才暴露。

**要点**：
1. **声明**：`AndroidManifest.xml` 必须声明 `POST_NOTIFICATIONS`（Android 13+ 通知可见的前提）、定位/相机/蓝牙等资源锚点权限。
2. **运行时申请**：`MainActivity.onCreate()` 批量申请尚未授予的危险权限（通知/定位/相机/蓝牙），仿 Aurelay 的 `requestAllPermissions()`，但收敛为「保活实际需要」的项（不照搬其 30+ 项全量测试申请）。
3. **后台定位单独申请**：`ACCESS_BACKGROUND_LOCATION` 与前台定位同批会被系统直接拒绝，需二次单独申请。
4. **电池优化引导**：`guideBatteryOptimization()` 一次性引导，用户拒绝后系统会阻止再次弹窗。

**权限 vs 耗电**：申请（授权）权限本身不耗电，只是「资格」；真正耗电的是 `ResourceHolder` 持有资源（持续监听定位/传感器/蓝牙/相机等），而后者已由「资源锚点」开关控制——关掉即释放、零耗电。因此「全量申请权限 + 设置页自由开关」可同时满足，不冲突。

## 四、设置页开关 UI（已集成）

Bridge 的设置页（`app/.../ui/SettingsPages.kt` 的 `KeepAliveSettings`）已提供全部开关 + 权限管理，供用户自由切换：

| 开关 | 对应 key | 默认 |
|---|---|---|
| 保活总开关 | `KEY_KEEP_ALIVE_ALWAYS` | 开 |
| 开机自启 | `KEY_KEEP_ALIVE_BOOT` | 开 |
| 双兜底唤醒 | `KEY_KEEP_ALIVE_FALLBACK` | 开 |
| 资源锚点 | `KEY_KEEP_ALIVE_RESOURCE` | 开 |
| 无声播放 | `KEY_KEEP_ALIVE_SILENT_PLAY` | 开 |
| 一像素锚点 | `KEY_KEEP_ALIVE_ONE_PIXEL` | 关 |
| 电池优化白名单 | 引导（`KeepAliveUtils`） | 手动 |
| 权限管理 | 显示/手动申请各危险权限授权状态 | 手动 |

**开关即时生效**：切换后调用 `KeepAliveService.refresh(context)`，服务收到 `ACTION_REFRESH` 会重新 `applyKeepAlive()`（先统一释放资源再按当前开关重新持有），保证 `双兜底`/`资源锚点` 等子开关也能动态生效。

## 五、待适配项

- `KeepAliveService` 通知小图标：替换为自己的图标资源。
- 心跳上报逻辑：按业务需要扩展 `KeepAliveService`。
