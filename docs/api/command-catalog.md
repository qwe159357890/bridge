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

### 3.3 扩展命令（固定流程，三步）

1. **后端**：在 `command_catalog.py` 的 `COMMANDS` 里加一条 `_cmd(...)`（必要时加类别）。
2. **客户端**：`Command.kt` companion 加命令名常量 + `app/.../di/CommandModule.kt`
   注册对应 `CommandExecutor`。
3. **文档**：
   - 有实现语义值得写（多端协作流程 / 客户端副作用 / 踩坑）→ 在
     `commands/{category}.md` 加 `## 命令名 中文名` 章节；
   - 同步 `commands/README.md` 索引表：有文档则把该命令行的 `—` 改成章节链接，
     无文档保持 `—`（索引表必须含所有命令）。

前端无需改动（自动拉取渲染）。

### 3.4 命令实现文档组织（docs/api/commands/）

命令会增长到几十上百个，**一命令一文档不可取**。实现文档按**命令分类聚合**，
与后台侧边栏分类树共用同一套 category key（同一分类心智）：

```
docs/api/
├── ws-client.md            协议层：WS 帧格式/握手/心跳/回执（跨命令，恒定 1 篇）
├── command-catalog.md      元数据层：本规范（怎么扩展命令，恒定 1 篇）
└── commands/               实现层：按分类聚合，文件名 = category key
    ├── README.md           命令级索引表（含「缺文档台账」）
    ├── control.md          🎛️ 控制类
    ├── data.md             📡 数据采集（按需创建）
    ├── media.md            🎬 音视频（按需创建）
    ├── interact.md         💬 交互（按需创建）
    └── system.md           ⚙️ 系统（按需创建）
```

四条规则（防膨胀的关键）：

1. **分类文件名 = category key**：后端 `CATEGORIES` 新增分类时才允许新增文档文件，
   文件数被分类数天然封顶；命令增长只表现为往文件里加 `## 命令名 中文名` 章节。
2. **文档不复制 SSOT**：参数 schema 的唯一权威是 `command_catalog.py`，
   文档只写 catalog 说不清的实现语义（机制/多端协作流程/缓存与副作用/踩坑）。
3. **写入门槛**：命令满足其一才写文档——多端协作流程、客户端副作用（缓存/状态）、
   有踩坑记录。简单命令（如 show_toast）不写，catalog 的 description 足够。
4. **INDEX.md 登记到目录级**：`api/` 段只列 `commands/` 一个入口（指向 README），
   不逐篇登记；README 是命令 → 文档章节的索引，无文档的命令也列出（标 `—`）。

示例：play_music 的音源三形态/缓存机制见
[commands/control.md](commands/control.md#play_music-播放音乐)。

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
