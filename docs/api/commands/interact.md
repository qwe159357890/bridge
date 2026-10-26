# 交互类命令（show_toast / speak / vibrate / show_dialog / send_notification）

> 命令定义单一事实来源：`server/app/core/command_catalog.py` 的 `interact` 分类。
> 各命令均无需 root。

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

## send_notification 发送通知

- 参数：`title`（可选，默认「服务器消息」）+ `content`（必填）+ `sound`
  （开关，默认开）+ `badge`（角标数字 1~99，默认 1）+ `mode`
  （`send`=发送通知 / `clear`=清除通知并归零角标）。
- 机制：`NotificationManager.notify` 发一条可滑除的普通通知（固定 id 2001，
  重复下发覆盖同一条）；通知驱动角标——**Android 无公开的桌面角标 API**，
  角标由各 ROM launcher 依据应用通知自动绘制：小米/华为/三星读取
  `Notification.number` 显示数字（小米 MIUI12+/HyperOS 官方支持），
  OPPO/vivo（含一加 9 系列的 ColorOS/OxygenOS）显示红点（数字角标仅部分
  ColorOS 版本支持）；保活前台服务的常驻通知不计入角标（系统排除常驻通知）。
- **一加/ColorOS 排查指引**（真机验证结论）：通知正常但桌面图标无红点/数字时，
  依次检查 ① 设置 → 通知与状态栏 → 通知管理 → Bridge → 「通知样式」选
  数字/圆点/混合角标（一加 9 Pro 手册明确此入口，红点即 ColorOS 对三方应用的
  常规上限）；② 设置 → 通知与状态栏 → 桌面角标 总开关 + 应用列表；
  ③ **「桌面角标」列表收录机制（真机解包对比结论）**：列表收录的是 Manifest
  里声明了厂商角标权限的应用——HBuilderX 应用（uni-app 运行时默认声明华为
  `CHANGE_BADGE` + vivo `BADGE_ICON`）能进列表而无需开放平台申请；Bridge
  v0.1.38 起补齐了华为/vivo/三星/第三方桌面 6 条角标权限声明，装新包后应
  出现在列表中。数字角标仍以厂商推送通道为准，红点走通知样式；
  ④ 通知渠道被单独静音不影响角标，但通知被「优化后台」冻结时角标可能不更新。
- 提示音/震动走两个预建渠道（声音属性创建后不可变，按 `sound` 选渠道）；
  渠道 `setShowBadge(true)`；`setAutoCancel(true)`——用户点开通知即消失，
  角标同步归零；点开通知跳转本应用（包名 launch intent）。
- `clear` 模式：`cancel(2001)` 撤回远程通知，角标归零（仅撤远程通知，
  不动保活常驻通知）。
- 权限：本应用通知总开关（API 33+ 为运行时权限 `POST_NOTIFICATIONS`）。
  未开启时自动打开系统通知设置页引导（同弹窗/锁屏引导模式），开启一次永久有效。
- 回执示例：
  - 成功：`已发送通知「服务器消息」，桌面角标显示 3`
  - 清除：`已清除通知，桌面角标已归零`
  - 未授权：`设备通知权限未开启。已在设备上打开通知设置页，请开启…后重新下发`
