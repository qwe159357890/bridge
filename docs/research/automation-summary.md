# 自动化模块阶段性总结

> 总结日期：2026-11-03。本文是自动化模块**当前阶段**的收尾总结：已完成什么、怎么做的、
> 还剩什么没验证、下一步做什么。功能怎么用见 `docs/guides/automation.md`，
> 功能全景清单见 `docs/research/droidline-feature-inventory.md`。

---

## 一、一句话总结

对标 **Droidline**（手机受控终端）与 **Auto.js/AutoX**（手机自动化脚本）两大开源项目，
逐行读完两者源码后，把「Droidline 能实现的 + AutoX 独有的」自动化能力，在 Bridge 里
做成了一套「**电脑 Python 脚本远程驱动手机**」的完整模块——手机端 66 条命令 +
Python SDK 70+ 方法 + 视觉（找图/OCR），已完成全量编译打包，待真机验证。

---

## 二、交付物总览

| 层 | 内容 | 位置 | 状态 |
|---|---|---|---|
| 客户端（Android） | 自动化代码收拢到 `com/automation` 包，11 子包 | `app/.../my/bridge/com/automation/` | ✅ 编译通过 |
| 客户端 | 关系查询引擎（Query/Matcher/SelectorParser/UiNodeView） | `automation/query/` | ✅ |
| 客户端 | 命令执行器（Ui/Device/Input/FileMedia/Notification 五族） | `automation/commands/` | ✅ |
| 客户端 | 无障碍服务 + 输入法（a11y）、底层 automator（移植 AutoX） | `automation/a11y/` `automation/automator/` | ✅ |
| 客户端 | 找色（ColorDetector 7 算法 + ColorFinder，纯 Java 去 OpenCV） | `automation/input/` | ✅ |
| 客户端 | 通知监听（NotificationListenerService） | `automation/notification/` | ✅ |
| 客户端 | 内嵌 HTTP RPC 服务（0.0.0.0:15161，67 命令白名单 + 12 端点） | `automation/rpc/` | ✅ |
| 服务端 | 命令目录 111 条（自动化 ui 类 70 条） | `server/app/core/command_catalog.py` | ✅ |
| SDK | Python 客户端（Bridge 类 70+ 方法 + Selector + Vision） | `sdk/python/bridge_rpc/` | ✅ |
| 文档 | 使用文档 / 阶段性总结 / 功能清单 / 实施路线 | `docs/guides/automation.md` 等 | ✅ |

---

## 三、已实现能力全景

### 3.1 无障碍动作层（Droidline UiCommands 全收）

| 类别 | 能力 | 数量 |
|---|---|---|
| 定位 | dump / find（含等待）/ exists / count / which / in_app | 6 |
| 点击 | click（三级降级）/ long_click / 坐标 tap | 3 |
| 手势 | gesture（swipe/click/long_click）/ 方向滑动 | 2 |
| 输入 | set_text（IME 兜底）/ clear / sendkey（键名/键码/文字三路） | 3 |
| 滚动/等待 | scroll_to（signature 防死循环）/ wait_idle / wait_gone | 3 |
| 读取 | get_text / checked / enabled / selected / last_toast / color | 6 |
| 全局动作 | back / home / recents / open_notifications / quick_settings | 5 |
| 状态 | keyboard_shown / screen_on / locked / wake / orientation | 5 |

### 3.2 关系查询引擎（本项目价值最高）

`row` / `below` / `above` / `left_of` / `right_of` / `has` / `inside` / `bounds`，
配合 11 字段条件 + 11 布尔 flag，实现「找价格 ¥99 那行的购买按钮」这类列表自动化。
支持链式 DSL 字符串与对象 JSON 双入口。

### 3.3 设备 / 应用命令层（Droidline + AutoX Device/AppUtils）

- 应用：launch（包名/按名）、open_url、intent、apps、installed、package_name/app_name 互查、open_app_setting。
- 设备：screen_on / locked / wake / orientation / info / get_mem / is_charging / set_brightness / get_brightness。

### 3.4 图像找色（AutoX 独有，纯 Java 去 OpenCV）

- ColorDetector 7 种判定算法（Equality/Difference/R/RGB/WeightedRGB/H/HS）。
- find_color（阈值找色）、find_multi_colors（多点找色，游戏自动化核心）、color（坐标取色）。

### 3.5 传感器 / 文件 / 媒体（AutoX 独有）

- 传感器：sensors（11 种类型一次性读取）。
- 文件：read / write / append / exists / list / copy / move / remove。
- 媒体：position / duration / state / seek / volume。

### 3.6 通知子域 + 剪贴板（Droidline，独立）

notifications / has_notification / notification_reply / notification_click /
notification_dismiss / clipboard（读走 IME 合法路径）。

### 3.7 结构化错误码 + RPC

- `AutomationError` 11 种错误码（NOT_FOUND / TIMEOUT / NO_ACCESSIBILITY / NO_IME / APP_NOT_FOUND 等），
  统一返回 `{success, error_code, retryable, message, data}`。
- RPC 服务：监听 `0.0.0.0:15161`，token 鉴权，67 命令白名单 + 12 个语义化 HTTP 端点 + 通用 `/exec`。
- Python SDK：Bridge 类 70+ 方法 + Selector 链式 DSL + Vision（opencv 找图 / OCR）。

---

## 四、与借鉴项目的对照结论

| | Droidline 贡献 | AutoX 贡献 | 我们取舍 |
|---|---|---|---|
| 借鉴 | 「脑+神经+眼睛」：关系查询、动作鲁棒性（三级降级/输入兜底）、设备/应用/通知命令、错误码 | 「手+感官」：按键/触摸注入、图像找色/找图、设备底层、Shell/Media/Files/Sensors | 全收（除架构冲突项） |
| 不借鉴 | 通讯/配对/租用/订阅、proxy VPN、脚本录制 | 界面(ui/)、线程(threads/)、Rhino 脚本引擎、Java 调用桥、APK 打包 | 偏离定位 / 不需要 |
| 后置 | — | RootAutomator（root 注入）、Shell（root 命令）、getevent 监听 | 需 root，P2 |

核心判断：**Droidline 走「无障碍手势（无 root）」路径，AutoX 走「root sendevent（需 root）」路径**，
两者互补。我们优先落地无障碍路径（零 root、覆盖 80% 场景），root 底层注入后置。

---

## 五、待真机验证清单（重要，勿跳过）

> 全部代码已编译打包，但**未经真机验证**。以下是最可能有坑的点，真机时优先排查：

1. **无障碍节点回收**：长时间运行后节点可能失效，三级降级 + catch IllegalStateException 是否真兜得住。
2. **IME 时序**：set_text 的「切输入法 → commitText → 切回」时序在真机上是否稳定（尤其密码框）。
3. **通知授权**：NotificationListenerService 是否被系统正确回调；Android 14+ 后台启动限制对 notification_click 的影响。
4. **关系查询正确性**：row/below/has 等关系在真实列表（RecyclerView）上是否准确定位。
5. **找色性能**：纯 Java `getPixel` 遍历全屏的耗时（大屏 + find_multi_colors 多点验证可能慢）。
6. **RPC 局域网/VNT 连通**：0.0.0.0 监听 + token 鉴权在真机三路（局域网/VNT/adb）是否都能连入。
7. **scroll_to / wait_idle**：signature 签名在真实滚动场景能否正确判断「到底了 / 静止了」。
8. **传感器**：各机型 SensorManager 返回格式差异。

### 已真机验证并修复的坑（记录留档）

> 以下是在真机测试中实际发现、已定位并修复的问题，供后续排查参考。

1. **桌面图标长按进「拖动态」而非弹菜单（v0.2.42 修复，已验证正常）**：
   - **现象**：代码长按桌面图标，弹出「图标拎起 + 半截操作气泡」的拖动态；手动长按弹「应用详情/分享/编辑/卸载」完整文字菜单；且调 `duration_ms` 完全无效果。
   - **根因**：桌面图标节点 `longClickable=true`，代码命中三级降级的第一级 `ACTION_LONG_CLICK`（`performLongClick()`）。该动作**无时长概念、读不到 `duration_ms`**（故调时长无效），且在 Launcher 上触发的是拖动态而非弹菜单。
   - **修复**：调整降级顺序为「**坐标手势优先、节点动作兜底**」。手势注入（`dispatchGesture` 真实按压）产生完整触摸事件流，模拟手动长按触发 `onLongClick` 弹菜单；失败才回退 `ACTION_LONG_CLICK`。
   - **教训**：无障碍 `ACTION_LONG_CLICK` 与 `dispatchGesture` 长按语义不同——前者走 `performLongClick()`（可能是拖动态），后者走完整触摸流（更接近手动）。桌面图标这类 `longClickable=true` 控件要弹菜单，必须手势优先。Droidline/AutoX 默认节点优先，同样会踩此坑。

---

## 六、遗留 TODO（暂不做，先放着）

1. **P2 后置能力**（需 root，价值有限，暂不投入）：
   - RootAutomator root 底层按键注入 + 多点触控；
   - Shell / ProcessShell root 命令执行；
   - getevent 输入事件监听 / 录制。
2. **传感器事件订阅**（当前只做一次性读取，change 事件走 WS 后置）。
3. **SDK 端 batch 事务**（手机端 batch 命令后置，脚本端 for 循环已够用）。
4. **命令历史「查看详情」弹窗**：66 条 ui 命令目前共用通用回执，按「命令历史查看详情按命令单独设计」约定，
   后续对高频命令（scroll_to/wait_idle/notifications 等）逐条单独设计详情弹窗。
5. ~~**调试台增强**~~（已落地）：调试台现支持**坐标级操作**（截图操作工具：坐标点击 / 坐标长按 / 坐标滑动，
   不依赖控件，解决「图标无 longClickable/clickable 标记则长按灰、无法调试」的问题）、**控件滑动**（右栏
   滑动测试，按方向/距离/时长生成起终点）、**目标设备自动记忆**（接全局 targetDevice store，刷新/切页不丢）。
   遗留「可视化拖拽画手势」已完成坐标滑动拖拽，其余多点触控手势后置。

---

## 七、下一步

1. **真机验证**（见 §五清单），逐项排查并修复。
2. 验证通过后，把 `docs/guides/automation.md` 按实际表现修正（尤其 FAQ 与坑）。
3. 按需对高频命令补「查看详情」弹窗。
4. 根据真机反馈，评估是否值得投入 P2 的 root 底层能力。
