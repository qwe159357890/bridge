# 交互类命令（show_toast / speak / vibrate / show_dialog）

> 命令定义单一事实来源：`server/app/core/command_catalog.py` 的 `interact` 分类。
> 四命令均无需 root。

## show_toast 弹提示

- 参数：`text`（必填，≤ 500 字）。
- 机制：标准 `Toast.makeText`（LENGTH_LONG），后台服务上下文直接可弹
  （Android 11+ 仅拦截后台自定义 View Toast，文本 Toast 不受限）。
- 无任何权限。

## speak 语音播报

- 参数：`text`（必填，≤ 1000 字）。
- 机制：系统 `TextToSpeech` 引擎，中文优先（`setLanguage(zh_CN)` 失败回退英文并注明），
  播完（`UtteranceProgressListener.onDone`）才回执；超时兜底 = 字数估算 + 8 秒
  （下限 15 秒，上限 120 秒）。
- 无权限；**依赖设备装有 TTS 引擎**（主流国产 ROM 自带；精简 ROM 缺失时
  回执「设备没有可用的 TTS 引擎」）。

## vibrate 震动

- 参数：`duration` 毫秒（50~10000，默认 500）。
- 机制：`VibrationEffect.createOneShot`（API 26+）；API 31+ 走 `VibratorManager`
  默认震动器。`VIBRATE` 普通权限（安装即授予）。

## show_dialog 弹窗

- 参数：`title`（可选）+ `content`（必填）。
- 机制：`TYPE_APPLICATION_OVERLAY` 全局悬浮窗对话框（白底圆角卡片 + 标题 +
  正文 + 「知道了」按钮），盖在任意应用之上，**停留到用户点击**。
- 权限：需「显示在其他应用上层」特殊权限（`SYSTEM_ALERT_WINDOW`，Manifest 已
  声明，需用户在系统设置开**一次**，永久有效）。未授权时命令自动弹系统设置页
  引导（同 lock_screen 的设备管理员引导模式，后台启动被拦时回执手动开启步骤）。
- **点击回传（交互闭环）**：
  1. 下发后立即回执：「已弹出对话框，等待用户点击『知道了』」；
  2. 用户点击后，客户端经 WS 业务通道上报 `{"event":"dialog_click","command_id":…}`；
  3. 服务端把「用户已点击弹窗确认」追加到该命令的 `result_message`，并经管理侧
     实时通道广播 `command_interaction` 事件——命令页即时弹提示 + 刷新历史，
     「回执信息」列可见点击结果。
- 同一时刻只保留一个弹窗（新弹窗顶掉旧的）。
