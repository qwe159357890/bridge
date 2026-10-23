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

## 三、设置页开关 UI（已集成）

Bridge 的设置页（`app/.../ui/SettingsPages.kt` 的 `KeepAliveSettings`）已提供全部开关，供用户自由切换：

| 开关 | 对应 key | 默认 |
|---|---|---|
| 保活总开关 | `KEY_KEEP_ALIVE_ALWAYS` | 开 |
| 开机自启 | `KEY_KEEP_ALIVE_BOOT` | 开 |
| 双兜底唤醒 | `KEY_KEEP_ALIVE_FALLBACK` | 开 |
| 资源锚点 | `KEY_KEEP_ALIVE_RESOURCE` | 开 |
| 无声播放 | `KEY_KEEP_ALIVE_SILENT_PLAY` | 开 |
| 一像素锚点 | `KEY_KEEP_ALIVE_ONE_PIXEL` | 关 |
| 电池优化白名单 | 引导（`KeepAliveUtils`） | 手动 |

**开关即时生效**：切换后调用 `KeepAliveService.refresh(context)`，服务收到 `ACTION_REFRESH` 会重新 `applyKeepAlive()`（先统一释放资源再按当前开关重新持有），保证 `双兜底`/`资源锚点` 等子开关也能动态生效。

## 四、待适配项

- `KeepAliveService` 通知小图标：替换为自己的图标资源。
- 心跳上报逻辑：按业务需要扩展 `KeepAliveService`。
