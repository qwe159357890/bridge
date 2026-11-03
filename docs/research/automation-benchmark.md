# 流程自动化借鉴项目对比评估：Droidline vs AutoX.js（+ Bridge 现状）

> 调研日期：2026-11-03
> 目标：用户新找到借鉴项目 `references/Droidline-main`，要求与 `references/Autoxjs_v6_ozobi-65823`（AutoX）一起评估，
> 明确「它们分别是什么流程自动化」「哪些能集成借鉴到 Bridge」。
> 关联文档：`docs/research/accessibility-enrichment.md`（AutoX 无障碍层已落地移植，7 条 ui_* 命令已实现）。
> 两个项目源码均只作研究借鉴，不考虑协议/侵权（用户已拍板，2026-11-02）。

---

## 一、结论先行（TL;DR）

| 项目 | 是什么 | 一句话定位 | 对 Bridge 的核心借鉴价值 |
|------|--------|-----------|------------------------|
| **AutoX.js** | 手机端 **脚本引擎**（JS 脚本直接在手机里跑） | 「脚本在**设备端**执行」 | ✅ 无障碍**动作层**（已落地：控件树 + 选择器 + 手势） |
| **Droidline** | PC 端 **控制服务器**（命令从 PC 下发、手机被动执行） | 「脚本在**PC/云端**执行，手机是受控终端」 | ✅ **连接/传输层 + 命令协议 + 服务器端视觉能力**（OCR/图像） |
| **Bridge** | 客户端 + 自建服务器（命令通道已具备） | 「命令从**后台/服务器**下发、客户端执行」 | 已有 Droidline 的「PC 控手机」雏形，缺的是它的**协议工程与视觉层** |

**核心判断**：

1. **两者是两种不同范式的自动化**——AutoX 是「设备端脚本引擎」，Droidline 是「远程受控终端 + 服务器编排」。Bridge 的定位（后台下发命令 → 客户端执行 → 回执）**天然更接近 Droidline**，而非 AutoX。
2. **无障碍动作层两者几乎同源**（都是 `AccessibilityNodeInfo` 封装 + 选择器 + 手势注入），Bridge 已经从 AutoX 移植了这部分，**Droidline 的动作层不需要重复移植**，但它的**选择器 DSL 更强大**（关系查询 `row`/`below`/`inside`、`nth`、内置等待），值得吸收。
3. **Droidline 真正的差异化价值在无障碍之外**，正是 Bridge 目前完全缺失的：
   - ① **服务器端视觉能力**（OCR + 图像模板匹配）——对无控件树的游戏/自绘界面
   - ② **命令协议工程**（错误码 + 可重试标记 + 幂等缓存 + 断线续传）
   - ③ **连接/传输层**（手机主动连出、TLS 加密、多路由、端到端加密）
   - ④ **输入法（IME）作为文本输入兜底**——当 `ACTION_SET_TEXT` 失效时的可靠替代
   - ⑤ **多设备编排 + 事件订阅 + 录制器**

---

## 二、两种范式的本质区别（先厘清，避免误判）

### 2.1 AutoX.js：设备端脚本引擎

```
用户写 JS 脚本 → 脚本在手机 App 内运行 → 通过无障碍 API 直接操作屏幕
     ↑ 脚本、引擎、执行、UI 全部在手机这一个进程内闭环
```

- 执行点：**手机端**（Rhino JS 引擎嵌入 APK 内）
- 强项：脚本可离线跑、不依赖 PC、可打包成独立 APK、有完整 IDE
- 弱项：脚本能力受限于手机算力；远程控制/多机管理/跨语言 SDK 都不是它的重点
- 与 Bridge 关系：Bridge 不想要「在手机上跑脚本」，所以 AutoX 的**引擎层**（Rhino、脚本运行时）对 Bridge 无借鉴价值；只有它的**无障碍动作层**有价值（已移植）。

### 2.2 Droidline：PC 服务器 + 手机受控终端

```
用户代码(Python/Node/CLI/HTTP/MCP) → PC 上的 droidline 服务器 → 加密链路 → 手机 Agent → 无障碍执行 → 回执
     ↑ 命令定义一次（spec/commands.json），多语言 SDK 自动生成
```

- 执行点：**PC/服务器端**（Go 写的 server + 多语言 SDK）
- 强项：多设备、跨语言、协议化、远程、AI 智能体（MCP）友好、服务器端视觉（OCR）
- 弱项：手机必须在线且主动连出；命令延迟高于设备端脚本
- 与 Bridge 关系：**Bridge 的现有架构（后台/服务器下发命令 → 客户端 WS 通道 → CommandModule 执行 → 回执）本质就是 Droidline 的「简化版」**，两者是同一范式，Droidline 是「这个范式做到极致」的参照系。

### 2.3 范式对照表

| 维度 | AutoX.js | Droidline | Bridge（现状） |
|------|----------|-----------|---------------|
| 自动化执行点 | 手机端脚本 | PC 端服务器 | 后台/服务器下发命令 |
| 客户端角色 | 脚本宿主 + 执行 | 受控终端 Agent | 命令执行器 |
| 命令定义方式 | JS API（手写） | **单一 spec + 代码生成** | Python catalog（手写） |
| 跨语言 SDK | 无（仅 JS） | Python/Node/.NET/CLI/HTTP/MCP | 无（仅后台 Web） |
| 视觉能力（OCR/图像） | 有（但依赖设备端模型，重） | **服务器端 Tesseract + 模板匹配** | **无** |
| 多设备 | 无 | ✅ 核心能力 | 有（多设备已支持） |
| 断线续传/幂等 | 无 | ✅ 命令缓存 | ⚠️ 无（命令可能重复执行） |
| 传输安全 | 无 | ✅ TLS + 端到端加密 | 已有 WS（可增强） |
| 输入法兜底 | 有（输入法辅助） | ✅ 独立 IME | ⚠️ 仅 ACTION_SET_TEXT |
| 无障碍动作层 | ✅ 完整 | ✅ 完整 | ✅ 已移植（AutoX） |

---

## 三、Droidline 逐层拆解（可借鉴点）

### 3.1 无障碍层（与 AutoX 同源，Bridge 已覆盖大半）

文件：`agent/.../a11y/{DroidAccessibilityService, UiNode, Query, UiCommands}.kt`

**与 AutoX 的异同**：

| 能力 | AutoX | Droidline | Bridge（已移植） |
|------|-------|-----------|-----------------|
| 控件树读取 | ✅ `UiObject` | ✅ `UiNode`（纯数据类，更轻） | ✅ |
| 选择器 | `UiSelector`（流式） | `Selector` + `Query`（对象式，更强） | ⚠️ 极简 DSL |
| 手势注入 | ✅ `GlobalActionAutomator` | ✅ `gesture/tap/swipe`（suspend 协程版） | ✅ |
| 节点动作 | click/setText/scroll | click/longClick/setText/scroll | ✅ 部分 |

**Droidline 无障碍层优于 AutoX、值得 Bridge 吸收的三个点**：

1. **关系选择器（Relation Query）**——AutoX 没有的杀手锏：
   - `row: {text:"WLAN"}`：找「同一行里有 WLAN 文本的那个元素」（列表项定位神器）
   - `below`/`above`/`left_of`/`right_of`：空间方位关系定位
   - `inside`/`has`：祖先/后代关系
   - 这套让「点开关、找列表项」这种模糊定位变得可写、可维护，是 Bridge 当前 `ui_find` 极简 DSL 完全做不到的。

2. **`nth` + 内置等待（`await`/`POLL_MS`）**：`touch`/`input` 默认等待元素出现 10 秒，`exists` 立即返回不等待。Bridge 的 `ui_find` 目前是「一次性查找，找不到就失败」，缺少「等待出现」语义。

3. **点击的「三级降级」契约**：`click()` 先试节点 `ACTION_CLICK` → 再试最近可点击祖先 → 最后降级到 `bounds` 中心点手势点击。这比 AutoX 更鲁棒，Bridge 可直接照搬。

### 3.2 输入法（IME）兜底 —— Bridge 完全没有、但价值极高

文件：`agent/.../ime/DroidKeyboard.kt`

**为什么重要**：`ACTION_SET_TEXT` 在很多场景失效（密码框、部分 App 自定义输入框、WebView 输入框），Droidline 的方案是——**内置一个极简输入法**，`input` 命令走三级降级：

```
ACTION_SET_TEXT → 失败/密码框 → 切换到 DroidKeyboard（IME）→ commitText 兜底
```

- 输入法还能 `sendKey`（键码）、`readClipboard`（剪贴板）、`replaceAll`（全选替换）。
- 对 Bridge：这是「让 `ui_set_text` 真正可靠」的关键补强。目前 Bridge 只做了 `ACTION_SET_TEXT`，遇到密码框/顽固输入框会静默失败。

### 3.3 服务器端视觉能力（OCR + 图像）—— 差异化最大的一块

文件：`server/internal/vision/{ocr.go, ...}`、命令 `ocr`/`ocr_find`/`ocr_tap`/`find_image`/`tap_image`/`find_images`/`wait_image_gone`/`image_exists`

**架构巧思**：OCR/图像识别**跑在服务器端**，不在手机上——

```
手机只出 screenshot（PNG 字节）→ 服务器用 Tesseract/模板匹配识别 → 返回坐标 → 再下发 tap
```

- 好处：手机端零模型负担（不膨胀 APK、不吃手机算力）；OCR 语言包、模板图库都放服务器。
- 命令：`ocr`（整屏识别）、`ocr_find`（找文字坐标）、`ocr_tap`（识别后直接点）、`find_image`（模板匹配找图）、`tap_image`（点图）。
- 对 Bridge：这是**解决「游戏/自绘界面无控件树」问题的正解**。Bridge 服务器端（Python）完全可以用 Tesseract/OpenCV 模板匹配实现同样的 `ocr_find`/`tap_image` 命令，手机端零改动（复用现有 screenshot 通道）。

### 3.4 命令协议工程（错误码 + 幂等 + 续传）—— 工程成熟度的体现

文件：`spec/commands.json`（错误码定义）、`agent/.../link/CommandCache.kt`、`spec/PROTOCOL.md`

三个 Bridge 目前缺失、但很值得借鉴的工程机制：

1. **统一错误码 + 可重试标记**：每个错误有 `code`（`NOT_FOUND`/`AMBIGUOUS`/`NOT_CLICKABLE`/`TIMEOUT`/`NO_ACCESSIBILITY`/`NO_IME`/`BAD_ARGS`…）+ `retryable` 布尔 + 多语言 `msg`。Bridge 的回执目前是「成功/失败 + 文本」，缺少结构化的错误码，后台和脚本无法程序化判断「该不该重试」。

2. **命令幂等缓存（CommandCache）**：`begin(id)` 标记 running → `finish(id, body)` 存结果。服务器重发同一 `id` 的命令时，直接从缓存取结果，**保证命令「不会执行两次」**。这正好呼应 Bridge 已有的「命令状态联动 + 幂等守卫」约定（`docs/api/commands/system.md`），但 Droidline 是**通用层**做的，Bridge 目前是**每条命令手写守卫**。

3. **断线续传（ResumeBuffer）+ 网络切断命令（batch + cuts_network）**：手机会缓存未完成的命令结果，重连后补发；`batch` 支持「先返回 accepted、执行完再发 result」的异步长任务。Bridge 的 VNT 断线重连已做了部分，但这套「命令级」的可靠投递语义值得参考。

### 3.5 连接/传输层（手机主动连出 + 加密）

文件：`agent/.../link/{LinkManager, SecureLink, Handshake, Discovery, Routes, Transports}.kt`

- 手机**主动连出**到服务器（UDP 广播发现 → TCP/TLS/WebSocket 多路由），手机无需开放端口。
- P-256 + AES-256-GCM 端到端加密，隧道/中继只转发密文。
- 对 Bridge：Bridge 已有主 WS 通道，但「手机主动连出 + 多路由 + 中继穿透」这套对「手机在任意网络（移动数据）下都能被控」有价值。不过这是**连接层**，与 Bridge 现有 keepalive/WS 架构需要评估是否重构，优先级较低。

### 3.6 其它（按需参考）

- **MCP 适配器**（`server/internal/mcp`）：把手机控制能力暴露为 MCP 工具，让 Claude/Cursor 等 AI 直接控手机。Bridge 未来若想「AI 控机」，可参考。
- **WebDriver/Appium 兼容**（`server/internal/webdriver`）：让 Appium 脚本控制手机。对 Bridge 无直接价值。
- **录制器（Recorder）**：记录手动操作生成脚本。对 Bridge 无直接价值。
- **设备所有者模式（DeviceOwner）**：`clear_data` 需要。Bridge 一般不需要。

---

## 四、给 Bridge 的落地建议（按价值/成本排序）

### 优先级 P0（高价值、低成本，建议尽快做）

| 借鉴点 | 来源 | 落地方式 | 预估成本 |
|--------|------|---------|---------|
| **输入法（IME）兜底** | Droidline `DroidKeyboard` | 新建 `BridgeKeyboard`（InputMethodService），`ui_set_text` 走「SET_TEXT → IME」降级 | 中 |
| **关系选择器 + nth + 等待** | Droidline `Query`/`Selector` | 扩展 Bridge `ui_find` 的 DSL，支持 `row`/`below`/`inside`/`nth` + `timeout` | 中 |
| **点击三级降级** | Droidline `click()` | 改 `executeUiClick`，加「祖先点击 → 坐标手势」降级 | 低 |

### 优先级 P1（高价值、中成本）

| 借鉴点 | 来源 | 落地方式 | 预估成本 |
|--------|------|---------|---------|
| **服务器端 OCR + 图像匹配** | Droidline `vision` | Bridge 服务器端加 `ocr`/`ocr_find`/`ocr_tap`/`find_image`/`tap_image` 命令（Tesseract/OpenCV），复用现有截图通道 | 中高 |
| **统一错误码 + 可重试标记** | Droidline `spec/commands.json` | Bridge 回执加结构化 `error_code` + `retryable` 字段 | 中 |

### 优先级 P2（参考，暂不急）

| 借鉴点 | 说明 |
|--------|------|
| 命令幂等缓存（CommandCache） | 通用层做，替代当前「逐命令手写守卫」；但 Bridge 已有约定，属优化非刚需 |
| 断线续传 + 网络切断 batch | 命令级可靠投递；Bridge VNT 重连已覆盖部分场景 |
| 多路由连接层（手机主动连出） | 需评估与现有 keepalive/WS 架构的关系，重构成本高 |
| MCP 适配器 | AI 控机方向，等需求明确再做 |

---

## 五、与 AutoX 结论的衔接

- **AutoX 给了 Bridge「无障碍动作层」**（已落地：`my.bridge.com.access.automator` 包 + 7 条 ui_* 命令）。
- **Droidline 给了 Bridge「动作层之外的工程能力」**：
  - 让动作层更鲁棒（IME 兜底、点击降级、关系选择器、等待语义）
  - 补上视觉能力（服务器端 OCR/图像，解决无控件树界面）
  - 提升协议成熟度（错误码、幂等、续传）

两者**互补而非替代**：AutoX 补「手」，Droidline 补「脑 + 神经 + 眼睛」。

---

## 六、参考资料

- `references/Droidline-main/`：`spec/commands.json`（82 命令）、`spec/PROTOCOL.md`（协议）、`agent/.../a11y/`（无障碍）、`agent/.../ime/DroidKeyboard.kt`（输入法）、`server/internal/vision/`（OCR/图像）、`agent/.../link/CommandCache.kt`（幂等缓存）
- `references/Autoxjs_v6_ozobi-65823/`：`automator/`（动作层，已移植）
- `docs/research/accessibility-enrichment.md`：AutoX 无障碍移植的完整记录
- Droidline 官方文档：https://droidline.dev/docs/
