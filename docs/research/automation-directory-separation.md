# 自动化模块目录分离方案

> 规划日期：2026-11-03（**逐行读源码后重写**，配合功能清单 ~100 项能力规划完整包结构）
> 目标：把「自动化」相关代码从三端（客户端/服务端/前端）中**独立成子模块**，与其他命令功能（控制/媒体/数据/系统…）解耦。自动化会膨胀出 ~100 项能力，混在一起会混乱。
> 关联文档：
> - `docs/research/droidline-feature-inventory.md`（功能全量清单，本方案的「内容来源」）
> - `docs/research/automation-implementation-plan.md`（实施顺序）
> - `docs/architecture/directory-convention.md`（目录规范）
> - `docs/api/command-catalog.md` §3.3（命令扩展三步规范）

---

## 一、现状盘点（自动化代码现在散落的位置）

### 1.1 客户端（Android/Kotlin）

| 位置 | 内容 | 角色 |
|------|------|------|
| `app/.../com/access/BridgeAccessibilityService.kt` | 无障碍服务（根节点缓存 + 手势 + 截屏 + imeCommitText） | 自动化「地基」 |
| `app/.../com/access/BridgeKeyboard.kt` | 极简 IME（InputMethodService） | 输入兜底 |
| `app/.../com/access/automator/`（24 文件） | AutoX 动作层移植（UiObject/UiGlobalSelector/UiObjectCollection/GlobalActionAutomator + filter 14 + search 3 + view 1） | 动作执行引擎 |
| `app/.../com/rpc/RpcServerController.kt` | 手写 HTTP/REST server（10 端点 + token 鉴权） | Python RPC 入口 |
| `app/.../com/di/CommandModule.kt`（约 400 行） | `parseSelector`/`nodeToJson`/`executeUiXxx`×7 + `executeRpcXxx`×3 | 命令执行器 |

**问题**：自动化代码横跨 `access`/`rpc`/`di` 三个包，且 `CommandModule.kt` 混着 10 个自动化执行器。按功能清单，自动化要新增 ~100 项能力（30+ 命令 + 关系查询 + 图像找色 + 设备底层 + 传感器 + 文件 + 通知子域），不先分目录，`CommandModule.kt` 会失控。

### 1.2 服务端（Python）

`server/app/core/command_catalog.py`：`ui` 类别 + 7 条 ui_* + 3 条 rpc_* 命令定义。单文件 51 条，自动化命令增至 30+ 后会加长。

### 1.3 前端（Vue3）

`admin/src/components/UiResultDetailDialog.vue` + `admin/src/views/command/index.vue` 的 `openDetail` 分流。自动化详情弹窗增多后会乱。

---

## 二、分离方案（目标目录结构）

### 2.1 客户端：新建 `com/automation` 包（自动化独立子模块）

按功能清单的**能力域**划分 11 个子包：

```
app/src/main/java/my/bridge/com/automation/        ← 新包（自动化独立子模块）
│
├── a11y/                                          ← 无障碍地基（从 access/ 迁入 + 扩展）
│   ├── BridgeAccessibilityService.kt              ← 迁入（根缓存+手势+截屏+imeCommitText）
│   │                                             ★ 扩展：多窗口根/current() activity/toast/键盘检测/changedAt
│   └── BridgeKeyboard.kt                          ← 迁入（IME 兜底）
│                                             ★ 扩展：sendKey/replaceAll/appendText/readClipboard
│
├── automator/                                      ← AutoX 动作引擎（24 文件整体迁入，不动）
│   ├── UiObject.kt / UiGlobalSelector.kt / UiObjectCollection.kt / GlobalActionAutomator.kt / ActionArgument.kt
│   ├── filter/（14 文件）/ search/（3 文件）/ view/（1 文件）
│
├── query/                                          ← ★新增：关系查询引擎（借鉴 Droidline）
│   ├── UiNode.kt                                   ← 节点模型（16 字段 + parent 指针 + walk + ancestors）
│   ├── Bounds.kt                                   ← bounds + cx/cy/area/isEmpty
│   ├── Selector.kt                                 ← 字段选择器 + id 裸名匹配
│   ├── Query.kt                                    ← 关系查询（row/below/inside/has/bounds + 布尔组合）
│   └── Matcher.kt                                  ← 降级/editableTarget/mainScrollable/signature
│
├── input/                                          ← ★新增：按键注入 + 图像找色（AutoX 移植）
│   ├── KeyNames.kt                                 ← 键名→键码映射（Droidline 移植）
│   ├── Colors.kt                                   ← 颜色工具（AutoX 移植）
│   ├── ColorDetector.kt                            ← 7 种颜色判定算法（AutoX 移植）
│   └── ColorFinder.kt                              ← findColor/findMultiColors（纯 Java 重写，去 OpenCV）
│
├── device/                                         ← ★新增：设备底层（AutoX Device + Droidline DeviceCommands）
│   ├── DeviceState.kt                              ← 亮度/音量/内存/充电/振动/屏幕唤醒（AutoX Device 移植）
│   ├── Apps.kt                                     ← 应用列表/版本/相似匹配（Droidline 移植）
│   ├── Sensors.kt                                  ← 传感器注册/读取（AutoX Sensors 移植）
│   ├── Media.kt                                    ← 音乐播放增强（AutoX Media 移植）
│   └── Files.kt                                    ← 文件读写/操作（AutoX Files 移植）
│
├── notification/                                   ← ★新增：通知子域（独立，借鉴 Droidline）
│   └── BridgeNotificationListener.kt               ← NotificationListenerService（list/has/reply/click/dismiss/filter）
│
├── rpc/
│   └── RpcServerController.kt                      ← 从 rpc/ 迁入（RPC 入口，扩展 30+ 端点）
│
└── commands/                                       ← ★命令执行器（从 CommandModule 拆出 + 按域新增）
    ├── UiCommands.kt                               ← ui_dump/find/click/set_text/gesture/back/home
    │                                               + scroll_to/wait_idle/wait_gone/clear/sendkey/exists/
    │                                                 get_text/checked/enabled/selected/count/which/in_app/
    │                                                 keyboard_shown/last_toast/color
    ├── DeviceCommands.kt                           ← launch/open_url/intent/apps/installed/screen_on/locked/
    │                                                 wake/battery/orientation/info/network/clipboard
    ├── InputCommands.kt                            ← find_color/find_multi_colors/sendkey 键码注入
    ├── SensorCommands.kt                           ← sensors 读取
    ├── FileCommands.kt                             ← file read/write/append/list/exists/copy/move/remove
    ├── MediaCommands.kt                            ← media 播放/seek/volume
    ├── NotificationCommands.kt                     ← notifications/has/reply/click/dismiss/filter
    ├── RpcCommands.kt                              ← rpc_start/stop/status
    └── SelectorParser.kt                           ← parseSelector + nodeToJson（从 CommandModule 拆出）
```

**迁移原则**：
1. **纯移动，不改逻辑**：`access/` → `automation/a11y/`、`access/automator/` → `automation/automator/`、`rpc/` → `automation/rpc/`，只改 package 声明 + import。
2. **执行器拆分**：把 `CommandModule.kt` 里的 10 个自动化执行器 + 辅助函数拆到 `automation/commands/` 按域分文件。`CommandModule.kt` 只保留**注册**（命令 → 执行器映射）。
3. **新增能力按域落包**：关系查询 → `query/`，图像找色 → `input/`，设备/传感器/媒体/文件 → `device/`，通知 → `notification/`，各命令执行器 → `commands/`。
4. **保留注册点**：`CommandModule` 仍是「命令 → 执行器」唯一注册中心（command-catalog §3.3），执行器本体搬走。

### 2.2 服务端：命令元数据按类别拆文件（后置）

```
server/app/core/commands/
├── __init__.py          ← 汇总 CATEGORIES + COMMANDS + ALLOWED_COMMANDS
├── control.py / data.py / media.py / interact.py / system.py
├── ui.py                ← ui_*（自动化动作层）
├── device.py            ← device_*/app_*/file_*/media_*（自动化设备域）
├── notification.py      ← notif_*（自动化通知域）
└── _helpers.py          ← _cat/_cmd/_param 构造器
```

> 不改接口（`catalog_payload()` 返回结构不变），可后置。

### 2.3 前端：详情弹窗按域分目录

```
admin/src/components/
├── vnt/VntResultDetailDialog.vue
├── ui/UiResultDetailDialog.vue          ← 迁入（ui_dump 树形可视化）
├── ui/DeviceResultDetailDialog.vue      ← ★新增（设备/应用/文件类回执）
├── ui/NotificationResultDetailDialog.vue ← ★新增（通知列表）
├── ssh/SshResultDetailDialog.vue
└── ...
```

自动化详情弹窗统一放 `ui/` 子目录，按命令单独设计（遵循「命令历史查看详情按命令单独设计」约定）。

---

## 三、实施步骤（第一步的落地顺序）

1. **客户端建包 + 迁移**（纯移动，风险最低）：
   - 新建 `com/automation/{a11y,automator,rpc,commands}`，迁入现有文件。
   - 全局替换 package 声明与 import。
   - 同步改 `AndroidManifest.xml` 的 service/receiver `android:name`（无障碍服务 + IME 服务完整类名）。
2. **执行器拆分**：从 `CommandModule.kt` 抽出 `parseSelector`/`nodeToJson`/10 个 executeXxx 到 `automation/commands/`，`CommandModule` 改委托。
3. **编译验证**：`compileDebugKotlin` 通过即迁移完成（纯重构）。
4. **服务端/前端拆分**：后置。

> **回滚安全**：纯重构（无逻辑变更），`git` 全跟踪，编译失败可 `git checkout` 回退。

---

## 四、后续能力落地到哪个子目录（配合 feature-inventory）

| 能力域 | 客户端落点 |
|--------|-----------|
| 关系查询引擎 Query/Matcher | `automation/query/` |
| 选择器 DSL 增强（裸名 id/nth） | `automation/commands/SelectorParser.kt` |
| 动作鲁棒性（降级/editableTarget/awaitRedraw） | `automation/commands/UiCommands.kt` |
| scroll_to/wait_idle/wait_gone/clear/sendkey/exists/count/which 等 | `automation/commands/UiCommands.kt` |
| launch/open_url/intent/apps/installed/screen_on/locked/wake 等 | `automation/commands/DeviceCommands.kt` |
| 设备底层（亮度/内存/振动/充电） | `automation/device/DeviceState.kt` |
| 传感器 | `automation/device/Sensors.kt` + `commands/SensorCommands.kt` |
| 图像找色（find_color/find_multi_colors） | `automation/input/` + `commands/InputCommands.kt` |
| 文件操作 | `automation/device/Files.kt` + `commands/FileCommands.kt` |
| 媒体增强 | `automation/device/Media.kt` + `commands/MediaCommands.kt` |
| 通知能力域 | `automation/notification/` + `commands/NotificationCommands.kt` |
| RPC 端点扩展（30+） | `automation/rpc/RpcServerController.kt` 的 `route()` |
| Python SDK 新方法 | `sdk/python/bridge_rpc/` |
| 图像找图（TemplateMatching） | **Python SDK 端**（opencv-python，不落客户端） |

---

## 五、命名约定（避免重踩 relay/switch 混淆坑）

| 包/目录 | 含义 | 边界 |
|---------|------|------|
| `automation/` | **界面自动化**（无障碍驱动 + Python RPC + 设备/输入/通知域） | 本方案新建 |
| `access/`/`rpc/` | 迁移期间的历史位置 | 迁移完成后删除 |
| `automation/input/` | 按键注入 + 图像找色 | 注意与项目已有的「input」概念区分（这里专指自动化输入） |
| `automation/device/` | 设备底层能力（亮度/传感器/文件/媒体） | 与 Bridge 已有的 `get_*` 命令区分（自动化域专用） |
| 与 `ssh/` 的区别 | SSH 是「远程终端 + SFTP」，非界面自动化 | 各自独立 |
| 与 `relay/`（音频中继）`switch/`（ESP32 继电器）的区别 | 完全不同域 | 勿混 |

---

## 六、风险与注意

1. **Manifest 类名**：无障碍服务（`BridgeAccessibilityService`）+ IME（`BridgeKeyboard`）+ 新增通知监听（`BridgeNotificationListener`）在 `AndroidManifest.xml` 用完整类名注册，迁移后必须同步改。
2. **通知监听服务**：`NotificationListenerService` 需 `android.permission.BIND_NOTIFICATION_LISTENER_SERVICE` + `intent-filter`，是新增的独立 service 节点。
3. **旧包删除**：迁移完成、编译通过后删 `access/`/`rpc/`，避免 grep 混淆。
4. **import 遗漏**：`SettingsPages.kt` 等引用了 access 包的地方，迁移时一并改。
5. **ColorFinder 去 OpenCV**：AutoX 的 ColorFinder/TemplateMatching 依赖 OpenCV（重依赖），找色用纯 Java `Bitmap.getPixel` 循环替代，找图放 SDK 端——**不要引入 OpenCV**（避免 APK 体积 + 构建复杂度暴涨）。

---

## 七、参考资料

- `docs/architecture/directory-convention.md`
- `docs/api/command-catalog.md` §3.3（命令扩展三步）
- `docs/research/droidline-feature-inventory.md`（功能清单，本方案「内容来源」）
- 项目记忆：`relay/` vs `switch/` 命名区分教训
