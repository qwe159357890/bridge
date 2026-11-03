# 界面自动化命令（ui）

> 分类 key = `ui`（「界面自动化」）。v0.2.39 引入，对标 autox 无障碍引擎。
> **v0.2.40 起已扩展到 66 条 ui_* 命令**（动作/设备/找色/传感器/文件/媒体/通知全量）。
> 本页仅详述早期 7 条核心命令的实现语义；全量命令清单与语义见
> `docs/research/droidline-feature-inventory.md`，使用方式见 `docs/guides/automation.md`。
> 依赖设备开启「Bridge 辅助服务」（无障碍），且 XML 已声明
> `canRetrieveWindowContent=true` + `canPerformGestures=true`。
>
> **耗电红线**：全部命令「按需拉取、零常驻轮询」——控件树只在命令触发时遍历一次，
> 无障碍服务仅订阅窗口切换（低频）用于缓存根节点，不订阅滚动/内容变化等高频事件。
> 详见 `docs/research/accessibility-enrichment.md`。

## 前置条件

1. 设备端：系统设置 → 无障碍 → 开启「Bridge 辅助服务」。
2. 改过 XML 配置后，需在无障碍里**重新开关一次**本服务才生效。
3. 未开启时，所有 ui 命令回执 `success=false`「无障碍服务未开启」。

---

## ui_dump 导出界面结构

- 导出当前前台窗口的控件树（DFS 遍历），返回 JSON 数组。
- 每节点含：`text` / `desc` / `id` / `className` / `package` / `bounds`（左,上,右,下）/
  `clickable` / `editable` / `enabled`。
- 参数：`max_nodes`（默认 500，上限 2000）、`max_depth`（默认 20，上限 50），超限截断。
- 用途：远程查看界面结构，辅助编写 `ui_find` 的选择器。

## ui_find 查找控件

- 按选择器 DSL 查找，返回匹配节点 JSON 列表（字段同上）。
- 参数：`selector`（必填）、`max_results`（默认 20，上限 200）。

### 选择器 DSL 语法（与 autox 对齐，链式组合）

| 方法 | 说明 | 例 |
|------|------|-----|
| `text(x)` | 文本精确匹配 | `text("登录")` |
| `textContains(x)` | 文本包含 | `textContains("确定")` |
| `textStartsWith(x)` / `textEndsWith(x)` | 文本前缀/后缀 | `textStartsWith("欢迎")` |
| `textMatches(re)` | 文本正则 | `textMatches(".*确定.*")` |
| `id(x)` / `idContains(x)` | 资源 id | `id("com.xx:id/btn")` |
| `desc(x)` / `descContains(x)` | contentDescription | `desc("搜索")` |
| `className(x)` / `classNameContains(x)` | 类名（`Button` 自动补 `android.widget.`） | `className("EditText")` |
| `packageName(x)` | 包名 | `packageName("com.tencent.mm")` |
| `clickable()` / `enabled()` / `scrollable()` / `editable()` | 布尔过滤（可带 `(true/false)`） | `clickable().enabled()` |

组合例：`className("EditText").enabled()`、`textContains("确定").clickable()`。

> 实现为极简正则解析器（`CommandModule.parseSelector`），暂不支持 bounds/depth/row 等
> 高级条件与 BFS/DFS 显式切换；正则按需后续补齐。

## ui_click 点击控件

- 优先按选择器定位（`selector` 有值时），否则按坐标（`x`/`y`）。
- `index`（默认 0）在多匹配时选第几个。
- **三级降级**（借鉴 Droidline）：节点 `ACTION_CLICK` → 最近可点击祖先 `ACTION_CLICK`
  → 节点 bounds 中心坐标手势。前两级失败自动降级，显著提升点击鲁棒性。
- 主动写操作，后台需二次确认。

## ui_set_text 输入文本

- 向可编辑控件写入文本，带 **IME 兜底**（借鉴 Droidline）：
  1. 第一级：`ACTION_SET_TEXT`（要求控件 `editable`）；
  2. 第二级：点击聚焦目标 → 切换 Bridge 极简输入法 → `commitText` → 切回原输入法。
  - 解决密码框、自定义输入框、WebView 输入框等 `ACTION_SET_TEXT` 失效的场景。
  - IME 兜底需用户在「系统设置 → 语言与输入法」启用「Bridge 输入辅助」（一次性）。

## ui_find 查找控件（支持等待出现）

- 按选择器 DSL 查找，返回匹配节点 JSON 列表。
- 参数：`selector`（必填）、`max_results`（默认 20）、`timeout_ms`（默认 0，等待出现毫秒数）。
- `timeout_ms > 0` 时轮询查找（间隔 300ms）直到命中或超时，实现「等待控件出现」语义。

## ui_gesture 手势操作

- 坐标级手势：`swipe`（默认）/ `click` / `long_click`。
- `swipe` 需 `x1/y1/x2/y2`；`click`/`long_click` 仅需 `x1/y1`。
- `duration_ms`（默认 300，滑动/长按有效）。

## ui_back / ui_home 全局动作

- `ui_back`：`GLOBAL_ACTION_BACK`，等同用户按返回键。
- `ui_home`：`GLOBAL_ACTION_HOME`，返回桌面。
- 均为 `performGlobalAction`，无额外参数。

---

## Python RPC（rpc_start / rpc_stop / rpc_status）

> 手机内嵌 HTTP/REST 服务，供 Python 脚本按需连入驱动自动化。通讯方向与 Droidline
> 相反（手机被动监听、Python 主动连入）。详见 `docs/research/automation-summary.md`。
> **面向小白的使用说明见 `docs/guides/automation.md`**（连接三步、能力全景、FAQ）。

### rpc_start 启动 RPC 服务

- 在设备本机拉起内嵌 HTTP/REST 服务，监听 **`0.0.0.0`（所有网卡）**，**默认端口固定 15161**。
- 支持从**本机（需 adb forward）/ 局域网 IP（如 192.168.31.109）/ VNT 虚拟网 IP（如 86.74.128.88）**任一可达地址连入。
- 参数：`port`（默认 15161，一般无需改）。
- 回执返回实际端口与鉴权 token（**Python 脚本每次请求必须带 token**，安全红线）。
- 幂等守卫：已运行时重复下发回「已在运行」（沿用 VNT/SSH 状态联动约定）。

### rpc_stop 停止 RPC 服务

- 停止服务，释放端口与线程池。未运行时直接收状态。

### rpc_status 查询 RPC 状态

- 只读本地快照：端口 / token / 已处理请求数，不触发网络操作。

### HTTP 端点（均需 token 鉴权）

| 端点 | 方法 | 参数 | 对应命令 |
|------|------|------|---------|
| `/dump` | GET | max_nodes, max_depth | ui_dump |
| `/find` | POST | selector, max_results, timeout_ms | ui_find |
| `/click` | POST | selector 或 x,y | ui_click |
| `/long_click` | POST | x, y, duration_ms | ui_long_click |
| `/set_text` | POST | selector + text | ui_set_text |
| `/gesture` | POST | action, x1,y1,x2,y2, duration_ms | ui_gesture |
| `/swipe` | POST | x1,y1,x2,y2, duration_ms | ui_gesture(swipe) |
| `/back` | POST | — | ui_back |
| `/home` | POST | — | ui_home |
| `/screenshot` | GET | — | 截屏（JPEG 二进制） |
| `/status` | GET | — | 无障碍就绪状态 |
| `/exec` | POST | command + 命令参数 | 通用入口（67 命令白名单，见下） |

返回结构统一：`{"success", "message", "data", "error_code", "retryable"}`。

`/exec` 白名单（67 条，即 `RpcServerController.rpcAllowedCommands`）：ui 元素/动作
（dump/find/click/long_click/set_text/gesture/scroll_to/wait_idle/wait_gone/clear/sendkey/
exists/get_text/checked/enabled/selected/count/which/in_app/keyboard_shown/last_toast/color/
recents/open_notifications/quick_settings）、设备/应用（launch/open_url/intent/apps/installed/
screen_on/locked/wake/orientation/info/launch_app/package_name/app_name/open_app_setting/
set_brightness/get_brightness/get_mem/is_charging）、找色/传感器（find_color/find_multi_colors/
sensors）、文件/媒体（file_*/media_*）、通知/剪贴板（notifications/has_notification/
notification_reply/notification_click/notification_dismiss/clipboard）。完整清单与语义见
`docs/research/droidline-feature-inventory.md`。

### Python SDK

见 `sdk/python/`（`bridge_rpc` 包）：`Bridge` 类 70+ 方法（`dump/find/click/set_text/swipe/
scroll_to/wait_idle/launch/find_color/sensors/notifications/clipboard/...`）、`Selector` 链式 DSL、
`Vision`（opencv 找图 + OCR）。分场景可运行示例见 `sdk/python/examples/`，使用文档见
`docs/guides/automation.md`。

---

## 实现要点与坑

1. **根节点获取**：`BridgeAccessibilityService.currentRoot()` 优先走窗口切换事件缓存的快照
   （避免命令频繁时反复跨进程 IPC），取不到再实时 `rootInActiveWindow`。
2. **节点失效兜底**：`UiObject` 所有 `performAction` 均 catch `IllegalStateException` 返回
   false（移植自 autox），保证「控件失效不崩、如实回执」。
3. **节点回收**：DFS/BFS 对非目标节点自动 `recycle()`；命令执行器用完根节点也 `recycle()`。
4. **可编辑判断**：`UiObject.editable()` 映射 `isEditable`（autox 未包装此方法，移植时补充）。
5. **移植裁剪记录**：ozobi 魔改版 DFS/BFS 里的 `root.refresh()`/`isRefresh` 因 `UiObject` 无
   `refresh()` 方法无法编译，已剔除恢复 autox 原始稳定逻辑（见调研文档 §4.2 ⚠️）。
