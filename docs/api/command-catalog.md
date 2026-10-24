# 命令目录与下发页设计（命令元数据规范）

> 本文档定义「管理后台可下发的命令」的**单一事实来源**与**下发页交互设计**。
> 解决命令数量增长后「单个下拉框找不到命令」的问题。

## 1. 问题与目标

命令会从个位数增长到几十上百个，且天然分属多类（控制、数据采集、音视频、交互、系统等）。
平铺下拉框（即使带搜索）会让用户「找不到命令」：翻找费劲、搜索需要先知道命令名。

目标：命令再多、类别再多，用户仍能**轻松、直观地找到并选中命令**，且**参数可配置下发**。

## 2. 核心决策

| 决策点 | 结论 |
|---|---|
| 命令清单单一事实来源 | **后端统一定义**（`server/app/core/command_catalog.py`），前端动态拉取，客户端由后端下发 |
| 下发页交互形态 | 左侧**分类树** + 右侧**命令卡片墙** + 选中命令后的**动态参数面板** |
| 下发白名单 | 从命令目录派生，避免与服务端/客户端脱节 |
| 命令参数 | 声明式 schema（type/label/required/default 等），前端按类型渲染控件 |

## 3. 命令目录（单一事实来源）

文件：`server/app/core/command_catalog.py`

### 3.1 数据结构

```python
# 类别
{"key": "control", "label": "控制", "icon": "🎛️", "description": "..."}

# 命令
{
    "name": "play_music",       # 命令名（下发给客户端、与客户端 Command 常量一致）
    "label": "播放音乐",         # 界面中文名
    "category": "control",       # 所属类别 key
    "icon": "▶️",                # 图标（Emoji 轻量图标）
    "description": "...",        # 一句话说明
    "params": [ ... ],           # 参数 schema（见下）
}

# 参数
{
    "name": "url",               # 参数键（下发 params 里的 key）
    "label": "音乐地址",          # 界面中文名
    "type": "text",              # 控件类型：text/number/switch/select/textarea
    "required": True,
    "default": None,
    "placeholder": "...",
    "options": [{"label": "...", "value": "..."}],  # 仅 select
    "description": "...",
}
```

### 3.2 参数控件类型

| type | 前端控件 | 说明 |
|---|---|---|
| `text` | el-input | 单行文本 |
| `number` | el-input-number | 数字 |
| `switch` | el-switch | 布尔开关 |
| `select` | el-select | 下拉（需 options） |
| `textarea` | el-input type=textarea | 多行文本 |

### 3.3 扩展命令

新增一条命令只需两处：

1. **后端**：在 `command_catalog.py` 的 `COMMANDS` 里加一条 `_cmd(...)`（必要时加类别）。
2. **客户端**：在 `app/.../di/CommandModule.kt` 注册对应 `CommandExecutor`，并在
   `Command.kt` 的 companion 加命令名常量。

前端无需改动（自动拉取渲染）。

## 4. meta 接口

- `GET /api/commands/meta`（需 admin 鉴权）
- 返回：`{"categories": [...], "commands": [...]}`
- 位置：`server/app/api/v1/commands.py` 的 `command_meta`

下发白名单 `ALLOWED_COMMANDS` 由目录派生：`{c["name"] for c in COMMANDS}`。

## 5. 下发页交互（管理后台）

文件：`admin/src/views/command/index.vue`

分三步：

1. **选择目标设备**（复用 DevicePicker，仅在线设备）
2. **选择命令**：左侧分类树（带命令计数），右侧卡片墙（图标 + 中文名 + 说明），
   顶部可搜索（按名称/命令名/说明模糊匹配，搜索覆盖当前分类）
3. **命令参数**：选中命令后，按 `params` schema 动态渲染参数表单（无参数则提示可直接下发）

选中命令后点「下发命令」，前端把填好的参数组装成 `params` 提交 `POST /api/commands`。

命令历史列表展示命令时用 `icon + label` 替代原始命令名（`commandLabelOf`）。

## 6. 三端对齐关系

```
后端 command_catalog.py  ──┐
                           ├──► 前端 /api/commands/meta 动态渲染（无需写死）
客户端 Command.kt 常量 ─────┘
客户端 CommandModule.kt  ──────► 注册执行器（命令真正落地）
```

命令名的**字符串值**是三端对齐的锚点：后端 `COMMANDS[].name` = 客户端 `Command` 常量 =
前端 `CommandDef.name`（下发时原样传给后端）。
