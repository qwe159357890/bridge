# 提交信息规范（commit-convention）

> 本文档约定 Bridge 项目 Git 提交信息（commit message）的写法，所有开发者必须遵守。

## 一、核心约定

### 1. 提交描述必须使用汉语

所有提交的**描述部分（subject 与 body）一律使用简体中文**，禁止纯英文描述。

- 这既符合团队母语习惯，也便于在 `git log`、GitHub 提交历史中快速检索理解。
- 例外：技术专有名词、命令名、文件路径、字段名等可直接保留原文（如 `WebSocket`、`upload_log`、`command_id`）。

### 2. 提交描述必须尽可能详细

- **禁止笼统的提交**，例如 `update`、`fix`、`修改`、`优化`、`调整` 这类无信息量的描述。
- 每条提交要说清「**做了什么、为什么、影响范围**」，让不熟悉上下文的人看历史也能理解。
- 多模块、多文件的大改动，用 **body（正文）分点列出**每处改动，必要时标注关键文件路径。

## 二、提交格式

采用「类型 + 中文描述」的前缀风格，与常见 Conventional Commits 兼容，但描述用中文：

```
<类型>: <中文主题>

<可选的正文，分点说明改动细节>
```

类型取值：

| 类型 | 含义 | 示例 |
|---|---|---|
| `feat` | 新功能 | `feat: 命令下发页重构（分类导航+命令卡片墙+参数面板）` |
| `fix` | 修复 bug | `fix: 心跳同步上报保活时长，解决管理后台时长冻结为 0 秒` |
| `docs` | 文档改动 | `docs: 新增三端术语约定（客户端/服务端/管理后台）` |
| `refactor` | 重构（不改变行为） | `refactor: 抽取设备昵称统一由 device_registry 提供` |
| `chore` | 构建/依赖/杂项 | `chore: bump version to 0.1.14` |
| `style` | 格式化（不影响逻辑） | `style: 统一 Kotlin 代码缩进` |
| `test` | 测试 | `test: 补充命令回执存储层单测` |

## 三、正反示例

**❌ 不推荐（笼统、无信息量）：**

```
update
fix
修改
优化代码
调整一下
```

**✅ 推荐（中文 + 详细）：**

```
feat: 命令下发页重构（分类导航+命令卡片墙+参数面板，后端命令目录单一事实来源）

- 后端新增 command_catalog.py：命令目录单一事实来源（5类别19命令+参数schema），白名单派生
- 后端 commands.py 新增 GET /api/commands/meta，前端动态拉取渲染
- 前端命令下发页改为命令主导布局：左侧分类导航+右侧卡片墙+动态参数面板
- 新增命令元数据规范文档 docs/api/command-catalog.md
```

## 四、关联说明

- 代码规范见 `docs/dev/coding-style.md`（待补充）。
- Git 协作流程见 `docs/dev/git-workflow.md`（待补充）。
- 构建发布走 GitHub Actions，推送方式见 `docs/dev/release-pipeline.md`（含 git 协议被阻断时的 API 通道）。
