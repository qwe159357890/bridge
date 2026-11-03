# 自动化模块实施路线（分阶段执行蓝图）

> 规划日期：2026-11-03（**逐行读源码后重写**，覆盖 Droidline 全收 + AutoX 补缺的 ~100 项能力）
> 状态：**待实施**（本文件是「照文档实现」的执行蓝图）
> 关联文档（权威来源，实施时对照）：
> - `docs/research/droidline-feature-inventory.md`（功能全量清单，逐行梳理两个项目）
> - `docs/research/automation-directory-separation.md`（第一步：目录分离方案）
> - `docs/api/command-catalog.md` §3.3（命令扩展三步规范）

---

## 一、总览

用户要求分三步：

| 步 | 内容 | 交付物 | 状态 |
|---|---|---|---|
| **第一步** | 三端自动化目录分离 | `automation-directory-separation.md` | ✅ 方案已定，待实施迁移 |
| **第二步** | 全面梳理两个项目功能（逐行读源码） | `droidline-feature-inventory.md` | ✅ 已重写完成 |
| **第三步** | 照文档逐一实现 | 本文件（实施蓝图） | ⏳ 待执行 |

第三步按**依赖关系**分 6 个批次，每批次独立可验收。核心原则：**先打地基（目录分离 + 查询引擎 + 错误码），再堆能力（动作 → 设备 → 输入 → 通知 → SDK）**。

---

## 二、批次 0：目录分离（纯重构，无行为变化）

> 前置步骤，先把代码收拢到 `automation/` 包。

**步骤**（详见 `automation-directory-separation.md` §三）：
1. 新建 `com/automation/{a11y,automator,rpc,commands}`，迁入现有文件。
2. 全局替换 package + import。
3. 同步改 `AndroidManifest.xml` 的 service `android:name`。
4. 拆 `CommandModule.kt` 执行器到 `automation/commands/`，CommandModule 改委托。
5. `compileDebugKotlin` 通过 → 删旧 `access/`/`rpc/` 包。

**验收**：编译通过 + 真机 ui_dump/ui_click/rpc_start 行为不变。

---

## 三、批次 1（P0）：地基 —— 查询引擎 + 错误码 + 选择器

> 这批是「关系查询 + 结构化错误」两块地基，后续所有动作命令都建立其上。

### 1.1 关系查询引擎（`automation/query/`）

移植 Droidline `Query.kt` + `UiNode.kt`：

| 子项 | 内容 | 来源 |
|------|------|------|
| UiNode 树模型 | 16 字段 + **parent 指针**（构建时回填）+ walk + ancestors | `UiNode.kt` |
| Bounds | l/t/r/b + cx/cy/area/isEmpty | `UiNode.kt` |
| Selector | 字段选择器 + **id 裸名匹配** + text/desc/class 正则 | `UiNode.kt` |
| Query | 关系条件 row/below/above/left_of/right_of/has/inside/bounds + 布尔 flag + 字段 AND | `Query.kt` |
| Matcher | clickableAncestor/longClickableAncestor/editableTarget/mainScrollable/signature | `UiNode.kt` |

**关键改造**：现有 `UiObject`（autox 移植）无 parent 指针，需新建轻量 `UiNode` 包装层（读 AccessibilityNodeInfo 快照成纯数据节点供查询，动作仍走 UiObject）或给 UiObject 补 parent。

**落点**：选择器解析器新增「对象形式」入口（`{...}` JSON），`ui_find`/RPC `/find` 支持关系查询。

### 1.2 结构化错误码 + retryable

对齐 `droidline-feature-inventory.md` §五错误码表：

| code | retryable | 语义 |
|------|-----------|------|
| NOT_FOUND | ✅ | 超时未找到目标 |
| AMBIGUOUS | ❌ | 多元素匹配 |
| NOT_CLICKABLE | ✅ | 找到但点击失败 |
| TIMEOUT | ✅ | 操作超时 |
| NO_ACCESSIBILITY | ❌ | 无障碍未开 |
| NO_IME | ❌ | 输入法未选 |
| NO_PERMISSION | ❌ | 权限缺失 |
| APP_NOT_FOUND | ❌ | 包未装（附相似包） |
| BAD_ARGS | ❌ | 参数无效 |
| UNSUPPORTED | ❌ | Android 版本不支持 |
| UNKNOWN_CMD | ❌ | 未知命令 |

`RpcServerController` 统一返回 `{success, error_code, retryable, message, data}`，Python SDK 的 `RpcError` 携带 retryable 供程序化重试。

### 1.3 选择器 DSL 增强

- `id` 裸名匹配（一行改动）。
- `nth` 统一参数（覆盖全部元素命令，兼容旧 `index`）。
- `ui_dump` 返回补 `package/activity/width/height` 顶层元信息 + `all_windows` 选项 + password 脱敏。

**验收**：关系查询脚本（如「找价格 ¥99 那行的购买按钮」）跑通；错误码区分可重试/不可重试。

---

## 四、批次 2（P0）：动作层增强（Droidline UiCommands 全收）

> 把现有 7 条 ui_* 扩展到 ~30 条，补全 Droidline 的动作全集。

### 4.1 动作鲁棒性补齐

| 能力 | 现状 | 动作 |
|------|------|------|
| 点击/长按 `via` 返回（node/parent/gesture） | 点击有降级，无 via | 补 |
| 长按三级降级 | 无降级 | 补 |
| editableTarget（TextInputLayout 包裹兼容） | 无 | 补 |
| awaitRedraw（输入后等重绘） | 无 | 补 |
| password 脱敏 | 无 | 补 |

### 4.2 新增元素动作命令

| 命令 | 语义 | 优先级 |
|------|------|--------|
| `ui_scroll_to` | 滚动直到可见（signature 防死循环） | **高** |
| `ui_wait_idle` | 画面静止检测 | **高** |
| `ui_wait_gone` | 等元素消失 | 中 |
| `ui_clear` | 清空输入 | 中 |
| `ui_sendkey` | 键名/键码/文字三路 | 中 |
| `ui_exists` | 元素是否存在 | 中 |
| `ui_get_text` | 元素文本 | 中 |
| `ui_checked`/`ui_enabled`/`ui_selected` | 读 flag | 中 |
| `ui_count` | 匹配数量 | 中 |
| `ui_which` | 多候选哪个先出现 | 中 |
| `ui_in_app` | 某包是否前台 | 中 |
| `ui_keyboard_shown` | 软键盘是否显示 | 中 |
| `ui_last_toast` | 最近 toast | 中 |
| `ui_color` | 坐标像素色 | 中 |

### 4.3 方向滑动增强

`ui_gesture` 的 swipe 支持方向字符串 `up/down/left/right`（按屏幕 60% 中线自动算坐标，`directionLine`）。

### 4.4 全局动作补齐

`ui_recents`/`ui_open_notifications`/`ui_quick_settings`（performGlobalAction，现有 back/home/lock 已覆盖）。

**验收**：scroll_to 滚动列表到目标、wait_idle 等动画结束、clear/sendkey 输入框操作均跑通。

---

## 五、批次 3（P0）：设备 + 应用命令层（Droidline DeviceCommands + AutoX Device/AppUtils）

### 5.1 应用命令（Droidline）

| 命令 | 语义 |
|------|------|
| `ui_launch` | 启动应用（可指定 activity，检查 exported） |
| `ui_open_url` | ACTION_VIEW 开 URL |
| `ui_intent` | 按 action 开界面（Settings 等） |
| `ui_apps` | 已装包列表（含 system 开关） |
| `ui_installed` | 某包版本名 |

### 5.2 设备状态命令（Droidline DeviceCommands）

| 命令 | 语义 |
|------|------|
| `ui_screen_on` | 屏幕是否亮（isInteractive） |
| `ui_locked` | 是否锁屏（KeyguardManager） |
| `ui_wake` | 点亮并解锁（WakeActivity） |
| `ui_orientation` | 横竖屏 |
| `ui_battery` | 电量/充电/温度（已有 get_battery，自动化域独立一份） |
| `ui_network` | 连接类型/飞行模式/计费 |
| `ui_info` | 型号/系统/屏幕/就绪汇总 |

### 5.3 设备底层（AutoX Device 独有，Droidline 无）

| 能力 | 语义 | 落点 |
|------|------|------|
| `set_brightness`/`get_brightness` | 亮度读写（WRITE_SETTINGS） | `device/DeviceState.kt` |
| `get_mem` | 总/可用内存 | `device/DeviceState.kt` |
| `is_charging` | 充电状态 | `device/DeviceState.kt` |
| `vibrate` | 振动（Vibrator） | `device/DeviceState.kt` |
| 设备静态信息 | model/brand/sdk/release/fingerprint 等 | `device/DeviceState.kt`（info 扩展） |
| `get_android_id` | AndroidId | `device/DeviceState.kt`（选择性） |

### 5.4 APP 增强（AutoX AppUtils 独有）

| 能力 | 语义 |
|------|------|
| `ui_launch_app` | 按应用名启动（launchApp） |
| `get_package_name`/`get_app_name` | 包名/应用名互查 |
| `open_app_setting` | 打开应用设置页 |

**验收**：launch 启动应用、set_brightness 调亮度、vibrate 振动、sensors 读值均跑通。

---

## 六、批次 4（P1）：输入增强 + 图像找色 + 传感器 + 文件 + 媒体

### 6.1 输入法增强（Droidline DroidKeyboard + KeyNames）

给 BridgeKeyboard 补：
- `sendKey(keycode)`（sendDownUpKeyEvents）
- `replaceAll(text)`（selectAll + commit，clear 依赖）
- `appendText(text)`（input append 依赖）
- `readClipboard()`（Android 10+ 读剪贴板唯一合法路径）

加 `KeyNames` 键名映射（enter/tab/del/backspace/space/escape/up/down/left/right/search/menu/page_up/page_down/move_home/move_end 等 17 键）。

### 6.2 图像找色（AutoX ColorFinder/ColorDetector/Colors，纯 Java 去 OpenCV）

| 能力 | 语义 |
|------|------|
| `ui_find_color` | 找色（支持 threshold + 7 种 ColorDetector 算法） |
| `ui_find_multi_colors` | 多点找色（firstColor + 相对坐标多点验证，游戏自动化核心） |

**关键**：用 `Bitmap.getPixel` 循环替代 OpenCV（`inRange`/`findNonZero`），避免引入重量依赖。ColorDetector 7 种算法纯计算零依赖，直接移植。

### 6.3 传感器（AutoX Sensors）

| 命令 | 语义 |
|------|------|
| `ui_sensors` | 读取指定传感器当前值（一次性） |
| （事件订阅后置） | 传感器 change 事件走 WS，P2 评估 |

传感器类型：ACCELEROMETER/MAGNETIC_FIELD/GYROSCOPE/LIGHT/TEMPERATURE/PRESSURE/PROXIMITY/GRAVITY/LINEAR_ACCELERATION/RELATIVE_HUMIDITY。

### 6.4 文件操作（AutoX Files）

| 命令 | 语义 |
|------|------|
| `ui_file_read`/`ui_file_write`/`ui_file_append` | 读写追加（文本/字节） |
| `ui_file_exists`/`ui_file_list` | 存在性/列目录 |
| `ui_file_copy`/`ui_file_move`/`ui_file_remove` | 复制/移动/删除 |
| `ui_file_create`/`ui_file_remove_dir` | 创建/删目录 |

### 6.5 媒体增强（AutoX Media）

| 命令 | 语义 |
|------|------|
| `ui_media_seek` | 音乐 seekTo |
| `ui_media_volume` | 播放音量 |
| `ui_media_position`/`ui_media_duration` | 播放位置/时长 |
| `ui_media_state` | 播放状态（isPlaying） |

**验收**：sendkey 发键码、find_multi_colors 游戏找色、sensors 读加速度、file 读写、media seek 均跑通。

---

## 七、批次 5（P1）：通知子域 + 剪贴板 + RPC 端点扩展

### 7.1 通知子域（Droidline NotificationListener，独立）

`automation/notification/BridgeNotificationListener.kt`（NotificationListenerService）：

| 命令 | 语义 |
|------|------|
| `ui_notifications` | 当前通知列表（key/package/title/text/lines/time/actions） |
| `ui_has_notification` | 是否有匹配通知（text/textContains/title/package） |
| `ui_notification_reply` | 内联回复（RemoteInput） |
| `ui_notification_click` | 点开通知（PendingIntent.send + Android 14+ 后台模式） |
| `ui_notification_dismiss` | 清除通知 |
| `ui_notify_filter` | 允许列表（白名单） |

**依赖**：`BIND_NOTIFICATION_LISTENER_SERVICE` 权限 + 用户手动授权 + Manifest 注册。

### 7.2 剪贴板（Droidline DeviceCommands.clipboard）

| 命令 | 语义 |
|------|------|
| `ui_clipboard` | 读（BridgeKeyboard.readClipboard）/写（ClipboardManager） |

### 7.3 RPC 端点扩展（30+）

把上述所有「元素/动作/设备/状态/输入/通知」命令暴露为 RPC `/xxx` 端点（凡 Python 脚本流程控制需要的都要暴露：exists/in_app/screen_on/count/which/color 等）。

**验收**：验证码自动化（通知监听 + 读验证码 + 回填）端到端跑通。

---

## 八、批次 6（P1）：Python SDK 视觉能力 + 通用能力

### 8.1 SDK 视觉（不放手机，脚本跑在 PC）

`sdk/python/bridge_rpc/vision.py`：
- 图像找图（opencv-python `matchTemplate`，对应 Droidline find_image/tap_image）
- OCR（Tesseract / paddleocr，对应 Droidline ocr/ocr_find/ocr_tap）
- 依赖手机端只提供「截屏字节流」（已有 screenshot 端点）

### 8.2 SDK batch 等价物

SDK 端 `for` 循环 + 可选事务封装（手机端 batch 后置，断网续跑价值待评估）。

### 8.3 SDK 方法补全

对应批次 2-5 新增的所有命令，SDK 各加一个方法（`phone.scroll_to(...)`、`phone.wait_idle(...)`、`phone.launch(...)`、`phone.find_color(...)`、`phone.sensors(...)`、`phone.notifications(...)` 等）。

---

## 九、批次 7（P2）：后置评估，暂不做

| 能力 | 原因 |
|------|------|
| RootAutomator（root 底层注入 + 多点触控） | 需 root + native 二进制，无障碍手势已覆盖 |
| Shell/ProcessShell（root 命令执行） | 需 root + 终端库 |
| InputEventObserver/TouchObserver（getevent 监听） | 需 root，录制/监听场景 |
| 图像找图（手机端 TemplateMatching/OpenCV） | 放 SDK 端，不引入 OpenCV |
| 多设备配对/租用/订阅（Droidline server 12 条） | 通讯方向相反 |
| proxy 按应用 VPN | 架构冲突 |
| clear_data | 需 Device Owner 系统权限 |
| record 脚本录制 | 偏离定位 |
| 传感器事件订阅 | 走 WS，低优先 |

---

## 十、每批次落地规范（对齐 command-catalog §3.3）

每条新命令按三步走：
1. **服务端**：`command_catalog.py`（或拆分后的 `commands/{ui,device,notification}.py`）新增命令元数据。
2. **客户端**：`automation/commands/` 新增执行器 + `CommandModule` 注册。
3. **文档**：`docs/api/commands/` + `docs/INDEX.md` 索引。

RPC 端点同步：凡「元素/动作/状态判断/设备状态」类命令，评估加 `/xxx` HTTP 端点（脚本流程控制需要）。

---

## 十一、验收标准（每批次完成时）

1. `compileDebugKotlin` + `assembleDebug` 通过。
2. 服务端 catalog 命令白名单正确（`catalog_payload()`）。
3. 后台命令卡片能渲染 + 下发成功。
4. 新增 ui 命令有「查看详情」弹窗（按命令单独设计，遵循「命令历史查看详情按命令单独设计」约定）。
5. Python SDK 对应方法可用（RPC 命令）。
6. 文档更新到对应命令 md + `INDEX.md`。

---

## 十二、实施顺序总结

```
批次 0  目录分离（纯重构）
   ↓
批次 1  地基：关系查询引擎 + 错误码 + 选择器增强
   ↓
批次 2  动作层：Droidline UiCommands 全收（~30 命令）
   ↓
批次 3  设备+应用：DeviceCommands + AutoX Device/AppUtils
   ↓
批次 4  输入增强 + 图像找色 + 传感器 + 文件 + 媒体
   ↓
批次 5  通知子域 + 剪贴板 + RPC 端点扩展
   ↓
批次 6  Python SDK 视觉 + 通用能力补全
   ↓
批次 7  P2 后置（root 注入 / Shell / 找图 / 录制）
```

每批次完成后即可真机验收，不必等全部完成。批次 1-3 是核心（覆盖 Droidline 的「脑 + 神经」+ AutoX 的「手」），批次 4-6 是补全（AutoX 感官 + 通知 + SDK 视觉）。
