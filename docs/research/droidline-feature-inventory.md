# 自动化模块功能全量清单（Droidline + AutoX 逐行梳理）

> 规划日期：2026-11-03（**逐行读源码后重写**，替换早期仅基于 commands.json 的粗粒度版本）
> 参照源码：
> - `references/Droidline-main/agent/`（63 个 Kotlin 文件，主源码 48 个 + 测试 15 个）
> - `references/Autoxjs_v6_ozobi-65823/`（1002 个 Java 文件，核心在 `autojs` module 的 `runtime/api` + `core/inputevent` + `core/image` + `core/util`）
> 目标：**一行一行读完两个借鉴项目**，形成「两个项目能实现的自动化能力全景」，标注每项「本项目能否实现 + 如何实现 + 实现细节 + 取舍」，作为自动化模块的实施总蓝本。
> 关联文档：
> - `docs/research/automation-directory-separation.md`（第一步：三端目录分离方案）
> - `docs/research/automation-implementation-plan.md`（第三步：分批次实施顺序）
> - `docs/research/automation-benchmark.md`（Droidline vs AutoX 定位对比）
> - `docs/research/accessibility-enrichment.md`（AutoX 无障碍动作层移植，已落地 7 条 ui_*）

---

## 〇、方法论与结论先行（用户确立的原则）

**本次梳理的原则（用户 2026-11-03 明确）**：

1. **Droidline 能实现的，我们也要能实现**——除非偏离定位（脚本录制 proxy、按应用 VPN、多设备配对/租用/订阅）。
2. **AutoX 有而 Droidline 没有的，也要集成**——devices、APP、按键模拟、图像、Shell、Media、Files、Sensors 等常规模块。
3. **取舍边界**：界面（ui/）、多线程（threads/）、Java 调用桥（rhino/engine/）、脚本引擎（Rhino JS）等**不需要**；常规自动化能力**都要**。
4. **逐行读源码评估**，不能只看命令定义文件（`commands.json`）——早期版本只看了 spec 就下结论是错误的，本次已逐个文件读透。

**全局结论（三句话）**：

- Droidline 贡献「**脑 + 神经 + 眼睛**」：关系查询引擎（Query）、动作鲁棒性（三级降级/输入兜底/等待语义）、设备/应用/通知/个人数据四域命令、结构化错误码。
- AutoX 贡献「**手 + 感官**」：底层按键注入（RootAutomator）、键/触摸事件监听（InputEventObserver/TouchObserver）、图像找色/找图（Colors/ColorFinder/TemplateMatching）、设备底层（Device 亮度/音量/振动/内存）、Shell/Media/Files/Sensors 系统能力。
- 两者**互补不冲突**：Droidline 走「无障碍手势（无 root）」路径，AutoX 走「root sendevent（需 root）」路径——同一动作两种实现，root 设备可用更底层、更可靠的路径。

---

## 一、两个项目定位（一句话，避免误读）

| | Droidline | AutoX（Auto.js ozobi 版） |
|---|---|---|
| 本质 | 手机**受控终端** + PC 服务器 | 手机上跑 **JavaScript 脚本**的引擎 |
| 通讯 | 手机**连出**到 PC 服务器（WS） | 脚本在手机本地执行，无远程 |
| 核心资产 | 命令语义 + 关系查询 + 动作鲁棒性 + 错误码 | 动作层（automator）+ 设备/系统 API 层（runtime/api）+ 图像/按键/Shell |
| 我们借鉴 | 命令全集 + 查询引擎 + 鲁棒性 + 错误码 | devices/APP/按键模拟/图像/Shell/Media/Files/Sensors |
| 我们不借鉴 | 通讯/配对/租用/订阅、proxy VPN、脚本录制 | 界面(ui/)、线程(threads/)、Rhino 脚本引擎、Java 调用桥、APK 打包 |

---

## 二、Droidline 逐文件能力清单（48 个主源码文件）

### 2.1 a11y/ 目录（无障碍核心，6 文件）

#### 2.1.1 `DroidAccessibilityService.kt`（284 行）—— 无障碍服务本体

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| 窗口根节点获取 | `selectorRoots()`：app 窗口按 layer 降序 + 活动系统窗口（如通知栏）；`allWindowRoots()` 全部窗口。节点数预算 5000、深度 80 | ✅ 已实现（cachedRoot），**增量**：多窗口根（selectorRoots/allWindowRoots 区分） |
| 节点树构建 `build()` | 递归读 text/id/desc/class/pkg/bounds + 8 个 flag（clickable/longClickable/checkable/checked/enabled/focused/selected/scrollable/editable/password/visible） | ✅ 已实现（nodeToJson 40+ 字段） |
| 手势 `gesture()/tapAt()/swipe()` | `dispatchGesture` 封装，suspendCancellableCoroutine 转协程 | ✅ 已实现 |
| 截屏 `screenshot()` | Android 11+ `takeScreenshot`（HardwareBuffer→Bitmap）；**限流处理**：ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT 重试 5 次、间隔 350ms；SECURE_WINDOW 报错 | ✅ 已实现（投屏令牌），**增量**：限流错误码细分 + 重试 |
| 前台包/活动 `current()` | `appWindows` 顶层包名 + `lastActivity` 缓存；`currentSettled()` 等待 1.5s 拿到非空 | ⚠️ 有 get_current_page（仅应用级），**增量**：activity 精度 |
| Toast 捕获 | `TYPE_NOTIFICATION_STATE_CHANGED` 且非 Notification → 记录 `lastToast/lastToastAt` | ❌ 无，**补**（verification code 场景） |
| 键盘显示检测 `keyboardShown()` | 查 window 里有无 `TYPE_INPUT_METHOD` | ❌ 无，**补** |
| 屏幕变化事件 `emitScreenIfChanged()` | 300ms 防抖后发 screen 事件 | 🔶 我们走 WS 主动上报，可复用 |
| 窗口状态追踪 | `changedAt`/`lastChangeAt(pkg)`/`windowsChangedAt`（wait_idle 依赖） | ❌ 无，**补**（wait_idle 前置） |

#### 2.1.2 `UiCommands.kt`（424 行）—— 27 个动作执行器

这是 Droidline 的「动作全集」，**逐条列出**：

| # | 动作 | 语义 + 关键实现 | 我们现状 | 集成 |
|---|------|----------------|---------|------|
| 1 | `touch` | 等元素 + 点击，返回 `via`(node/parent/gesture) + 耗时 | ui_click | ⚠️ 补 via 返回 |
| 2 | `long_touch` | 等元素 + 长按（三级降级），返回 via | ui_gesture | ⚠️ 补长按降级 |
| 3 | `tap` | 坐标点击（duration 50ms） | ui_click 坐标 | ✅ |
| 4 | `long_tap` | 坐标长按（默认 800ms） | ui_gesture | ✅ |
| 5 | `swipe` | 滑动，**支持方向字符串**（up/down/left/right 按屏幕 60% 中线算）或四点坐标 | ui_gesture | ⚠️ 补方向字符串 |
| 6 | `scroll_to` | 滚动直到可见：`ACTION_SCROLL_FORWARD/BACKWARD`→方向滑动手势→`signature` 检测内容不变（死循环保护），max_swipes 默认 20 | ❌ | **补（高价值）** |
| 7 | `input` | 设文本：`ACTION_SET_TEXT`→（忽略/密码/无变化）IME 兜底；`append` 追加；`editableTarget` 兼容包裹；`awaitRedraw` 等重绘 | ui_set_text | ⚠️ 补 editableTarget + append + awaitRedraw |
| 8 | `clear` | 清空：set 空串 → IME `replaceAll("")` | ❌ | **补** |
| 9 | `sendkey` | 键名/键码/文字三路：全局动作（back/home/recents/notifications/quick_settings/lock）vs IME `sendKey(keycode)`/`commit(text)` | ❌ | **补** |
| 10 | `dump` | 导树，返回 package/activity/width/height/tree；`all_windows` 选项 | ui_dump | ⚠️ 补元信息 + all_windows |
| 11 | `current` | 前台 package/activity | get_current_page | ⚠️ 补 activity |
| 12 | `exists` | 元素是否存在（不等待） | ❌ | **补** |
| 13 | `wait` | 等元素出现（200ms 轮询 + timeout） | ui_find | ✅ |
| 14 | `wait_gone` | 等元素消失（轮询 + TIMEOUT） | ❌ | **补** |
| 15 | `wait_idle` | 等画面静止：`signature`（可见文本+bounds 哈希）连续 quiet ms 不变；超时 TIMEOUT | ❌ | **补（高价值）** |
| 16 | `find` | 等元素并返回节点（children=false） | ui_find | ✅ |
| 17 | `find_all` | 立即返回所有匹配 | ui_find max_results | ✅ |
| 18 | `get_text` | 元素文本（密码框返回 ""） | ❌ | **补** |
| 19 | `checked/enabled/selected` | 读节点 flag（布尔） | ❌ | **补** |
| 20 | `count` | 匹配数量 | ❌ | **补** |
| 21 | `which` | 多个候选中哪个先出现（返回序号或 -1） | ❌ | **补（多分支流程）** |
| 22 | `in_app` | 某包是否前台 | ❌ | **补** |
| 23 | `keyboard_shown` | 软键盘是否显示 | ❌ | **补** |
| 24 | `last_toast` | 最近 toast（max_age 秒内） | ❌ | **补** |
| 25 | `color` | 坐标像素色 #RRGGBB（截图取像素） | ❌ | **补** |
| 26 | `global back/home/recents/notifications/quick_settings/lock` | `performGlobalAction` | ui_back/ui_home/lock_screen | ⚠️ 补 recents/notifications/quick_settings |
| 27 | `screen_on/locked/wake`（在 device 域） | 见 2.2 | — | — |

#### 2.1.3 `Query.kt`（162 行）—— 关系查询引擎（**本项目最缺、价值最高**）

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| 字段条件 | text/textContains/textMatches/id/desc/descContains/descMatches/class/package（多条件 AND） | ⚠️ 有字段选择器，缺对象组合 |
| 布尔 flag 条件 | clickable/long_clickable/checkable/checked/enabled/focused/selected/scrollable/editable/password/visible | ❌ 补 |
| `bounds` 精确匹配 | [l,t,r,b] 四元组相等 | ❌ 补 |
| `has`（后代） | 子查询匹配某后代（self=false） | ❌ **补** |
| `inside`（祖先） | 子查询匹配某祖先 | ❌ **补** |
| `row`（同行/同列表项） | 找最近列表 item（RecyclerView/ListView/GridView 或 scrollable 多子项），或最近 clickable 祖先 | ❌ **补（列表自动化核心）** |
| `below/above/left_of/right_of` | 方向关系：先找锚点（子查询第一个匹配），按节点中心 vs 锚点 bounds 比较，结果按距离排序 | ❌ **补** |
| 嵌套深度限制 | MAX_DEPTH=8 | — |

#### 2.1.4 `UiNode.kt`（146 行）—— 节点模型 + Matcher 工具

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| `Bounds` | l/t/r/b + cx/cy/area/isEmpty 便捷属性 | ✅ |
| `UiNode` 模型 | 16 字段 + parent 指针（构建时回填）+ walk（前序）+ ancestors() | ⚠️ 有节点模型，**缺 parent 指针**（关系查询前提） |
| `toJson` | **password 脱敏**（`if(password) text=""`） | ⚠️ **补脱敏**（重要安全点） |
| `Selector` | by+value，`matchField` 含 **id 裸名匹配**（`!contains(':') && endsWith(":id/$value")`） | ⚠️ 补裸名匹配 |
| `Matcher.clickableAncestor` | 最近可点击祖先 | ✅ 已实现降级 |
| `Matcher.longClickableAncestor` | 最近可长按祖先 | ⚠️ 补 |
| `Matcher.editableTarget` | 自身或第一个 editable 后代（TextInputLayout 包裹兼容） | ⚠️ 补 |
| `Matcher.mainScrollable` | 最大 scrollable 节点（列表/页面容器） | ❌ 补（scroll_to 依赖） |
| `Matcher.signature` | 可见文本+desc+bounds 哈希（wait_idle/scroll_to 死循环检测） | ❌ 补 |

#### 2.1.5 `Recorder.kt`（78 行）—— 脚本录制

监听 TYPE_VIEW_CLICKED/LONG_CLICKED/TEXT_CHANGED，回传 action 事件 + 屏幕快照，密码脱敏，定时自关。

**判断**：偏「脚本录制工具」，我们定位是「脚本已写好」，**暂不做**（偏离定位，用户明确）。

### 2.2 device/ 目录（设备能力，7 文件）

#### 2.2.1 `DeviceCommands.kt`（245 行）

| 能力 | 实现细节 | 我们现状 | 集成 |
|------|---------|---------|------|
| `launch` | 启动应用：launch intent / leanback intent / 显式 activity（检查 exported）；awaitForeground 5s | ❌ | **补** |
| `open_url` | ACTION_VIEW 开 URL（可指定 package） | ❌ | **补** |
| `intent` | 按 action 开界面（含 CLEAR_TASK，Settings 页需） | ❌ | **补** |
| `clear_data` | 清应用数据（**需 Device Owner**） | ❌ | ❌ 不适用 |
| `chrome.go` | Chrome 开网址（new_tab / application_id 控制复用标签） | ❌ | 🔶 低优先 |
| `apps` | 已安装包列表（package/label/version/system，可含系统） | ❌ | **补** |
| `installed` | 某包版本名（未装返回 ""） | ❌ | **补** |
| `screen_on` | PowerManager.isInteractive | ❌ | **补** |
| `locked` | KeyguardManager.isKeyguardLocked | ❌ | **补** |
| `wake` | WakeActivity + FLAG_TURN_SCREEN_ON，等交互且解锁 | ❌ | **补** |
| `battery` | 电量/充电/温度（registerReceiver 粘性广播） | get_battery | ✅ |
| `orientation` | 横竖屏（Configuration.ORIENTATION） | ❌ | **补** |
| `screen_size` | DisplayManager.getRealMetrics | get_screen 相关 | ✅ |
| `info` | 型号/厂商/sdk/release/agent 版本/宽高/就绪状态 | 部分 | 🔶 补就绪汇总 |
| `network` | wifi/mobile/none + 飞行模式 + 计费（NetworkCapabilities） | get_network | ✅ |
| `clipboard` | 写（ClipboardManager）；读（Android 10+ 需当前 IME 的 readClipboard） | ❌ | **补**（复用 BridgeKeyboard） |

#### 2.2.2 `Capture.kt`（231 行）—— 截屏 + 取色

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| `screenshot` | Android 11+ 无障碍 takeScreenshot；9/10 用 MediaProjection（Projection 对象，虚拟显示 + ImageReader，静态帧复用 last） | ✅ 有投屏/截屏，**增量**：9/10 MediaProjection 降级 + format(png/jpeg)+quality+scale |
| `color` | 截图取 (x,y) 像素色 #RRGGBB | ❌ **补** |

#### 2.2.3 `Apps.kt`（81 行）

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| `list` | getInstalledPackages 列表（含 label/version/system） | **补** |
| `requireInstalled` | 未装抛 APP_NOT_FOUND + **相似包名推荐**（Levenshtein 距离 + 末尾段匹配） | **补**（错误提示友好） |
| `similar`/`score`/`levenshtein` | 相似度算法 | 🔶 可移植 |

#### 2.2.4 `Sms.kt`（117 行）/ `Calls.kt`（62 行）/ `Contacts.kt`（59 行）

| 能力 | 实现细节 | 我们现状 | 集成 |
|------|---------|---------|------|
| `sms` | Telephony.Sms/MMS 读（box=inbox/sent/all + address/contains/within 筛选 + MMS 文本/地址提取） | get_sms（更强，含上报） | ✅ 已有，**增量**：MMS 支持 |
| `calls` | CallLog 读（type 筛选 + number/name 模糊） | get_call_log | ✅ 已有 |
| `contacts` | ContactsContract 读（name/numbers/emails + query 模糊） | get_contacts | ✅ 已有 |

### 2.3 ime/ 目录（输入法，2 文件）

#### 2.3.1 `DroidKeyboard.kt`（115 行）—— 极简 IME

| 能力 | 实现细节 | 我们现状（BridgeKeyboard） | 集成 |
|------|---------|---------------------------|------|
| `commit(text)` | commitText | ✅ 已有 | — |
| `sendKey(code)` | sendDownUpKeyEvents | ⚠️ **缺** | **补**（sendkey 命令依赖） |
| `replaceAll(text)` | selectAll + commit（batch edit） | ⚠️ 有 commitText，缺 selectAll | **补**（clear 依赖） |
| `appendText(text)` | 移光标到末尾 + commit | ❌ 缺 | **补**（input append 依赖） |
| `readClipboard()` | 当前 IME 读剪贴板（Android 10+ 唯一合法路径） | ❌ 缺 | **补**（clipboard 读依赖） |
| `isEnabled/isSelected/current` | 输入法启用/选中状态检测 | ⚠️ 部分 | 🔶 补状态检测 |

#### 2.3.2 `KeyNames.kt`（61 行）—— 键名映射

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| `CODES` 映射 | enter/tab/del/backspace/forward_del/space/escape/up/down/left/right/search/menu/page_up/page_down/move_home/move_end 等 17 个键名 → keycode | **补** |
| `GLOBAL` 集合 | back/home/recents/notifications/quick_settings/lock | 部分已有 |
| `resolve()` | key（键名/键码/文字）+ text 统一解析三路分流 | **补** |

### 2.4 cmd/ 目录（命令框架，3 文件）

#### 2.4.1 `CommandRunner.kt`（245 行）—— 命令分发 + batch + 错误码

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| 单线程串行执行 | Channel + 单线程 dispatcher（命令按到达顺序执行） | 🔶 我们 CommandModule 同步执行，可参考 |
| `dispatch()` 50 命令 | 权威分发表（见 2.1/2.2 逐条） | 参照 |
| `batch` | 批量命令：steps 数组 + stop_on_error；嵌套禁止 | 🔶 SDK 端可先等价实现 |
| `precheck` | requires 检查（a11y/notif/sms/calls/contacts）+ minSdk 检查 | **补**（依赖检查） |
| 错误码 `errorJson` | ok/error/msg/retryable + fields 展开 | ⚠️ 简化版，**对齐** |

#### 2.4.2 `CommandSpec.kt`（142 行）—— 命令元数据

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| `ParamSpec` | name/type/required/default/clientOnly | 🔶 参考 |
| `normalize` | [cmd, args...] 列表 → 参数对象，填默认值 | 🔶 参考 |
| `missingRequired` | 必填参数检查 | 🔶 参考 |
| `minSdk` 检查 | 版本不支持 → UNSUPPORTED | **补** |
| `render`/`retryable` | 错误模板渲染 + retryable 判定 | **补** |

#### 2.4.3 `CmdError.kt`（10 行）

错误类型封装（code + fields）。我们已有 RpcError，对齐即可。

### 2.5 notif/ 目录（通知，1 文件）

#### `DroidNotificationListener.kt`（153 行）—— 通知监听服务

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| `list(pkg)` | 当前通知列表（key/package/title/text/lines/time/actions） | **补**（独立子域） |
| `has(by,value)` | text/textContains/title/package 匹配 | **补** |
| `reply(key,text)` | 内联回复（RemoteInput） | **补** |
| `click(key)` | 点开通知（PendingIntent.send，Android 14+ 后台启动模式） | **补** |
| `dismiss(key)` | 清除通知 | **补** |
| `notify_filter` | 允许列表（白名单） | **补** |
| 语义 action 名 | semanticAction 映射（reply/mark_read/delete/archive/mute/thumbs_up...） | 🔶 参考 |

**判断**：需 `NotificationListenerService` + 用户手动授权，价值高（验证码自动化），**作为独立子域落地**。

### 2.6 Droidline 明确不借鉴的部分

| 目录/能力 | 原因 |
|----------|------|
| `link/`（13 文件：Discovery/Handshake/Envelope/SecureLink/Transports...） | 手机连出到服务器的发现/配对/握手/多路由，我们 RPC 方向相反 |
| `vpn/`（4 文件：LocalProxy/ProxyController/ProxyVpnService/Upstream） | 按应用 VPN 代理，需独立 VPN 服务 + 网络栈，架构冲突 |
| `crypto/`（DeviceIdentity/Kdf/P256/B64） | 配对握手加密，我们不需要 |
| `admin/DeviceOwner.kt` | Device Owner 系统权限（clear_data 依赖） |
| `service/UpdateChecker.kt` | 更新检查（我们有 check_update） |
| `ui/`（MainActivity/PairScreen/SetupScreen/StatusScreen...） | 界面，不需要 |

---

## 三、AutoX 逐文件能力清单（官方 API 层 + 底层模块）

> AutoX 有 1002 个 Java 文件，但**自动化能力**集中在几个目录。本节逐行梳理 `runtime/api`（官方 API 层）+ `core/inputevent`（按键模拟）+ `core/image`（图像）+ `core/util`（Shell）。**界面(ui/)、脚本引擎(rhino/engine/)、线程(threads/)、APK 打包(apkbuilder/) 不梳理**（用户明确排除）。

### 3.1 runtime/api 目录（官方 Auto.js API 层，14 文件）

#### 3.1.1 `Device.java`（463 行）—— **devices 模块（AutoX 独有，Droidline 无，高价值）**

这是用户点名的「devices 模块」，**逐条列出全部能力**：

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| 设备静态信息 | width/height/buildId/product/board/brand/device/model/fingerprint/sdkInt/release/securityPatch 等 20+ 字段（Build.*） | **补**（info 命令扩展） |
| 屏幕尺寸 | getCurScreenSize/getCurWidth/getCurHeight（getRealSize） | 已有 |
| 亮度 | getBrightness/getBrightnessMode/setBrightness/setBrightnessMode（Settings.System，需 WRITE_SETTINGS） | **补**（set_brightness 命令） |
| 音量 | getMusicVolume/getNotificationVolume/getAlarmVolume + 各自 Max + set（AudioManager） | ✅ 已有 set_volume/get_volume |
| 电量 | getBattery（粘性广播） | ✅ 已有 |
| 内存 | getTotalMem/getAvailMem（ActivityManager.MemoryInfo） | **补** |
| 充电状态 | isCharging（EXTRA_PLUGGED） | **补** |
| 保持唤醒 | keepAwake/keepScreenOn/keepScreenDim（PowerManager WakeLock，含 timeout 变体） | 🔶 我们有自己的保活体系，**参考不直接搬** |
| 屏幕状态 | isScreenOn/wakeUp/wakeUpIfNeeded（PowerManager） | **补** |
| 振动 | vibrate(millis)/cancelVibration（Vibrator） | **补**（vibrate 命令） |
| 设备标识 | getIMEI/getAndroidId/getMacAddress（多路 fallback：wifiInfo→interface→/sys 文件） | 🔶 隐私敏感，**选择性补**（AndroidId） |
| 导航栏检测 | checkDeviceHasNavigationBar/getVirtualBarHeight（反射 SystemProperties） | 🔶 低优先 |

#### 3.1.2 `Sensors.java`（166 行）—— **传感器模块（AutoX 独有）**

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| 传感器注册/注销 | register(name)/unregister/unregisterAll（SensorManager） | **补**（sensors 命令，一次性读取 + 事件订阅两种模式） |
| 传感器类型映射 | ACCELEROMETER/MAGNETIC_FIELD/ORIENTATION/GYROSCOPE/LIGHT/TEMPERATURE/PRESSURE/PROXIMITY/GRAVITY/LINEAR_ACCELERATION/RELATIVE_HUMIDITY + 反射兜底 | **补** |
| 延迟档位 | Delay.normal/ui/game/fastest（SENSOR_DELAY_*） | 参考 |
| 事件回调 | onSensorChanged/onAccuracyChanged → emit("change") | 🔶 我们走 RPC 请求-响应，事件订阅可后置 |

#### 3.1.3 `Media.java`（193 行）—— **媒体模块（AutoX 独有）**

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| 音乐播放 | playMusic(path, volume, looping)/pauseMusic/resumeMusic/stopMusic | ✅ 已有 music 相关命令，**增量**：seekTo/volume/looping |
| 播放状态 | isMusicPlaying/getMusicDuration/getMusicCurrentPosition | **补** |
| seek | musicSeekTo(m) | **补** |
| 媒体扫描 | scanFile(path)（MediaScannerConnection） | 🔶 低优先 |
| 状态机 | MediaPlayerWrapper 状态机（防 IllegalState） | 参考 |

#### 3.1.4 `Files.java`（247 行）—— **文件模块（AutoX 独有）**

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| 文件读写 | read/write/append/readBytes/writeBytes/appendBytes/open（PFiles） | **补**（file 命令族：read/write/append/exists/list） |
| 文件操作 | create/createIfNotExists/createWithDirs/ensureDir/copy/move/rename/remove/removeDir | **补** |
| 路径工具 | path/cwd/getSdcardPath/listDir/isFile/isDir/isEmptyDir/join/getExtension/getName/getNameWithoutExtension/getHumanReadableSize | **补** |

#### 3.1.5 `AppUtils.java`（140 行）—— **APP 模块（AutoX 独有，Droidline 部分重叠）**

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| 启动应用 | launchPackage/launchApp（按名找包再启动） | ✅ Droidline launch 已覆盖，**增量**：launchApp 按应用名 |
| 包名/应用名互查 | getPackageName(appName)/getAppName(pkg) | **补** |
| 打开应用设置 | openAppSetting(pkg) | **补** |
| 卸载 | uninstall(pkg)（ACTION_DELETE） | 🔶 需用户确认，低优先 |
| 打开/编辑文件 | viewFile/editFile（FileProvider） | 🔶 低优先 |

#### 3.1.6 `AbstractShell.java`（274 行）—— **Shell 抽象（按键注入 + 命令执行）**

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| 命令执行 | exec(command)/execAndWaitFor（su 或 sh） | 🔶 **root 命令执行**（我们无 shell 命令，**可补**） |
| 按键注入 | Tap/Swipe/KeyCode/Home/Back/Power/Up/Down/Left/Right/OK/VolumeUp/VolumeDown/Menu/Camera（`input` 命令） | **补**（sendkey 的另一种实现，无需 root 的 `input` 命令） |
| 文本输入 | Input/Text(text)（`input text`） | 🔶 参考 |
| 截屏 | Screencap(path)（`screencap`） | 🔶 参考 |
| sleep/usleep | exec sleep | 🔶 参考 |

#### 3.1.7 其余 API 文件（Console/Dialogs/Events/Engines/Plugins/SevenZip/Floaty）

| 文件 | 能力 | 集成 |
|------|------|------|
| `Console.java` | 控制台输出 | ❌ 脚本引擎概念，不需要 |
| `Dialogs.java` | 对话框 UI | ❌ 界面，不需要 |
| `Events.java` | 事件系统 | 🔶 我们走 WS 事件，参考 |
| `Engines.java` | 脚本引擎管理 | ❌ 脚本引擎，不需要 |
| `Plugins.java`/`SevenZip.java`/`Floaty.java` | 插件/解压/悬浮窗 | ❌ 不需要 |

### 3.2 core/inputevent 目录（按键模拟，**AutoX 独有，用户点名**）

#### 3.2.1 `RootAutomator.java`（327 行）—— **root 底层按键注入（高价值）**

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| 底层触摸 | touchDown/touchMove/touchUp（EV_ABS + ABS_MT_* 协议，**支持多点触控** slot 管理） | 🔶 **需 root**（写 /dev/input），**作为 root 设备备选路径** |
| tap/swipe/press/longPress | 坐标级操作（lerp 插值平滑滑动） | 🔶 同上 |
| 屏幕坐标缩放 | setScreenMetrics/scaleX/scaleY | 参考 |
| 底层依赖 | 依赖 `RootAutomatorEngine`（native 二进制，chmod 777 后 `-d 设备路径` 运行） | ⚠️ **需 root + native 二进制**，复杂 |

**判断**：RootAutomator 是「root 设备上的更底层、更可靠按键注入」，与 Droidline 的无障碍手势**互补**。但依赖 root + native 二进制，**建议后置**（P2），无障碍手势已覆盖绝大多数场景。

#### 3.2.2 `KeyEventDispatcher.java`（55 行）—— 键事件分发

监听音量上/下、拍照键（OnKeyListener）。**依赖 RootAutomator 的 getevent**，需 root。🔶 后置。

#### 3.2.3 `InputEventObserver.java`（145 行）—— 输入事件监听

`getevent -t` 解析所有输入事件（含时间戳）。**需 root**。🔶 后置（录制/监听场景）。

#### 3.2.4 `TouchObserver.java` / `ShellKeyObserver.java`

触摸/按键观察器。🔶 后置。

#### 3.2.5 `InputEventCodes.java` / `InputDevices.java`

输入事件常量表 + 触摸设备探测。参考。

### 3.3 core/image 目录（图像识别，**AutoX 独有，用户点名**）

#### 3.3.1 `Colors.java`（91 行）—— 颜色工具

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| 颜色常量 | BLACK/WHITE/RED/GREEN/BLUE/YELLOW/CYAN/MAGENTA/TRANSPARENT | 参考 |
| rgb/argb/parseColor | 颜色构造/解析 | 参考 |
| luminance | 亮度计算（BT.709 感知亮度） | 🔶 参考 |
| RGB↔HSV | RGBToHSV/colorToHSV/HSVToColor | 🔶 参考 |
| equals | 颜色相等（忽略 alpha） | 参考 |
| toString | #RRGGBB 格式化 | 参考 |

#### 3.3.2 `ColorDetector.java`（199 行）—— **7 种颜色判定算法（高价值）**

| 算法 | 语义 | 集成 |
|------|------|------|
| EqualityDetector | RGB 完全相等 | **补** |
| DifferenceDetector | |R-r|+|G-g|+|B-b| ≤ 3*threshold | **补** |
| RDistanceDetector | 仅 R 分量距离 | **补** |
| RGBDistanceDetector | 欧氏距离平方 ≤ 3*threshold² | **补** |
| WeightedRGBDistanceDetector | 加权 RGB 距离（人眼感知） | **补** |
| HDistanceDetector | 色相距离 | **补** |
| HSDistanceDetector | 色相+饱和度距离 | **补** |

**判断**：这是 AutoX `images.findColor` 的算法核心，**纯计算零依赖**，非常适合移植到「color 相关命令」（找色支持 threshold + 多种算法）。

#### 3.3.3 `ColorFinder.java`（132 行）—— 找色

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| findColorEquals | 精确找色（全屏/区域） | **补**（find_color 命令） |
| findColor | 阈值找色（threshold，区域缩放） | **补** |
| findAllPointsForColor | 找所有匹配点 | **补** |
| findMultiColors | **多点找色**（firstColor + 相对坐标多点验证，游戏自动化核心） | **补（高价值）** |

**判断**：ColorFinder 依赖 OpenCV（inRange/findNonZero），我们可**用纯 Java/Bitmap 循环替代**（getPixel 遍历），避免引入 OpenCV 重量依赖。`findMultiColors` 是游戏/无节点界面自动化的关键。

#### 3.3.4 `TemplateMatching.java`（248 行）—— 找图（图像金字塔）

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| fastTemplateMatching | OpenCV matchTemplate + 图像金字塔加速 + 强弱阈值 | 🔶 **需 OpenCV**（重依赖） |
| 金字塔加速 | 逐层缩放匹配，强阈值提前返回 | 🔶 同上 |

**判断**：找图依赖 OpenCV，**放 Python SDK 端**（脚本跑在 PC，用 opencv-python）更合理，手机端零新增命令。手机端只需「截屏字节流」+「findMultiColors 多点找色」覆盖轻量场景。

### 3.4 core/util 目录（Shell 执行）

#### `Shell.java`（321 行）/ `ProcessShell.java`（300 行）

| 能力 | 实现细节 | 集成 |
|------|---------|------|
| root 命令执行 | Shell 依赖 jackpal.androidterm（终端模拟库），su/sh 会话，exec/execAndWaitFor | 🔶 **需 root + 终端库**，后置 |
| ProcessShell | Runtime.exec 直接执行（无终端库） | 🔶 参考 |

**判断**：我们项目**目前没有 shell 命令执行能力**。AutoX 的 ProcessShell（Runtime.exec 一行）可作为「shell 命令」的基础，但**需 root 才有意义**（非 root 只能跑 app 沙箱内命令，价值有限）。建议 P2 评估。

---

## 四、集成决策汇总（Droidline ✅ 全收 + AutoX ✅ 补缺 + 取舍）

### 4.1 明确集成（Droidline 能实现 → 我们要实现）

**无障碍动作层**（在现有 7 条 ui_* 基础上扩展到 ~30 条）：
- 关系查询引擎 Query（row/below/above/left_of/right_of/has/inside/bounds + 布尔组合）
- 动作鲁棒性补齐：长按三级降级、editableTarget、awaitRedraw、mainScrollable、signature
- 新增动作：scroll_to、wait_idle、wait_gone、clear、sendkey、exists、get_text、checked/enabled/selected、count、which、in_app、keyboard_shown、last_toast、color
- 方向滑动（up/down/left/right 字符串）
- 节点 parent 指针 + password 脱敏 + id 裸名匹配

**设备/应用命令层**：
- launch/open_url/intent/apps/installed/screen_on/locked/wake/battery/orientation/info/network/clipboard
- recents/notifications/quick_settings 全局动作

**输入法增强**：
- BridgeKeyboard 补 sendKey/replaceAll/appendText/readClipboard + KeyNames 键名映射

**通知子域**（独立）：
- NotificationListenerService：list/has/reply/click/dismiss/filter

**结构化错误码 + retryable + minSdk/requires 检查**

### 4.2 明确集成（AutoX 独有、Droidline 无、不冲突）

- **Device 设备底层**：亮度读写、内存、充电状态、振动、屏幕唤醒、设备静态信息
- **Sensors 传感器**：注册/读取（加速度/陀螺/光/温度/压力/距离/重力/线性加速度/湿度）
- **图像找色**：Colors + ColorDetector（7 算法）+ ColorFinder（findColor/findMultiColors），纯 Java 实现避免 OpenCV
- **Media 增强**：seekTo/volume/looping/position/duration
- **Files 文件**：read/write/append/list/exists/copy/move/remove 等
- **APP 增强**：launchApp 按名、getPackageName/getAppName、openAppSetting
- **Shell 按键注入**：`input` 命令版 Tap/Swipe/KeyCode（无需 root 的按键码注入，补充 sendkey）

### 4.3 明确取舍（不做）

| 类别 | 具体 | 原因 |
|------|------|------|
| Droidline 通讯层 | link/（发现/配对/握手/多路由）、server 12 条（配对/租用/订阅） | 通讯方向相反 |
| Droidline 网络 | vpn/（proxy 按应用 VPN） | 架构冲突 |
| Droidline 录制 | Recorder | 偏离定位 |
| Droidline 权限 | DeviceOwner（clear_data） | 需系统权限 |
| Droidline 界面 | ui/ | 界面不需要 |
| AutoX 脚本引擎 | rhino/engine/inrt（Rhino JS）、ScriptRuntime | 脚本引擎不需要 |
| AutoX 界面 | core/ui/（XmlConverter/inflater/widget/attribute/nativeview） | 界面不需要 |
| AutoX 线程 | threads/looper/concurrent | 多线程不需要 |
| AutoX 调用桥 | Java 调用桥（ScriptBridges）、annotation | 不需要 |
| AutoX 打包 | apkbuilder/codegeneration | APK 打包器不需要 |
| AutoX 网络 | http/MutableOkHttp、web/WebXSocket | 我们有自己的网络层 |
| AutoX 悬浮窗 | Floaty、ConsoleFloaty | 悬浮窗不需要 |
| AutoX 录制 | record/（AccessibilityActionRecorder/InputEventRecorder） | 偏离定位 |
| AutoX OpenCV 找图 | TemplateMatching（图像金字塔）、opencv/ | 放 SDK 端 |

### 4.4 后置评估（P2，有价值但成本/风险高）

| 能力 | 原因 |
|------|------|
| RootAutomator（root 底层注入 + 多点触控） | 需 root + native 二进制，无障碍手势已覆盖 |
| Shell/ProcessShell（root 命令执行） | 需 root + 终端库 |
| InputEventObserver/TouchObserver（getevent 监听） | 需 root，录制/监听场景 |
| 图像找图（TemplateMatching） | 放 SDK 端，手机端不引入 OpenCV |

---

## 五、能力全景统计（结论）

| 来源 | 能力域 | 命令/能力数 | 集成 |
|------|--------|-----------|------|
| Droidline | 无障碍动作（UiCommands） | 27 动作 | ✅ 全收（补 ~20 条） |
| Droidline | 关系查询（Query） | 7 关系 + 11 字段 + 11 flag | ✅ 全收 |
| Droidline | 设备命令（DeviceCommands） | 17 命令 | ✅ 收 16（除 clear_data） |
| Droidline | 应用（Apps） | 4 能力 | ✅ 全收 |
| Droidline | 截屏取色（Capture） | 2 能力 | ✅ 全收 |
| Droidline | 输入法（DroidKeyboard/KeyNames） | 6 + 17 键名 | ✅ 全收 |
| Droidline | 通知（NotificationListener） | 7 能力 | ✅ 全收（独立子域） |
| Droidline | 命令框架（CommandRunner/Spec） | 错误码/batch/依赖检查 | ✅ 收（batch SDK 端） |
| AutoX | Device（设备底层） | ~20 能力 | ✅ 收（除 IMEI/Mac 隐私） |
| AutoX | Sensors | ~11 传感器 | ✅ 收 |
| AutoX | 图像找色（Colors/ColorDetector/ColorFinder） | 7 算法 + 4 找色 | ✅ 收（纯 Java） |
| AutoX | Media | 8 能力 | ✅ 收（增量） |
| AutoX | Files | ~20 能力 | ✅ 收 |
| AutoX | AppUtils | 5 能力 | ✅ 收（增量） |
| AutoX | Shell 按键注入（input 命令） | ~12 键 | ✅ 收（sendkey 补充） |
| — | **合计待新增** | **~100 项能力** | — |

> 这些能力将按 `automation-implementation-plan.md` 分批次落地，全部落在「自动化独立子目录」中（见 `automation-directory-separation.md`）。

---

## 六、参考资料

- `references/Droidline-main/agent/app/src/main/java/dev/droidline/agent/a11y/`（UiCommands/Query/UiNode/DroidAccessibilityService/Recorder）
- `references/Droidline-main/agent/app/src/main/java/dev/droidline/agent/device/`（DeviceCommands/Capture/Apps/Sms/Calls/Contacts/WakeActivity）
- `references/Droidline-main/agent/app/src/main/java/dev/droidline/agent/ime/`（DroidKeyboard/KeyNames）
- `references/Droidline-main/agent/app/src/main/java/dev/droidline/agent/cmd/`（CommandRunner/CommandSpec/CmdError）
- `references/Droidline-main/agent/app/src/main/java/dev/droidline/agent/notif/`（DroidNotificationListener）
- `references/Autoxjs_v6_ozobi-65823/autojs/src/main/java/com/stardust/autojs/runtime/api/`（Device/Sensors/Media/Files/AppUtils/AbstractShell）
- `references/Autoxjs_v6_ozobi-65823/autojs/src/main/java/com/stardust/autojs/core/inputevent/`（RootAutomator/KeyEventDispatcher/InputEventObserver）
- `references/Autoxjs_v6_ozobi-65823/autojs/src/main/java/com/stardust/autojs/core/image/`（Colors/ColorDetector/ColorFinder/TemplateMatching）
