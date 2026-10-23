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

## 三、待适配项

- `KeepAliveService` 通知小图标：替换为自己的图标资源。
- 心跳上报逻辑：按业务需要扩展 `KeepAliveService`。
