# Bridge 项目目录组织规范

> 本文档是 Bridge 项目的**目录组织「宪法」**，所有目录的创建、命名、归类都必须遵循本规范。
> 适用于：企业级大型多功能 Android 项目。
>
> 定位：**区分「应用模块架构」与「项目目录结构」**——
> - 应用模块架构（app/core/feature）描述的是**代码**如何分层；
> - 本项目目录结构描述的是**整个仓库**如何组织，包括文档、数据、资产、构建、参考等一切非代码产物。

---

## 一、顶层目录总览

```
Bridge/                             仓库根目录
├── app/                            应用壳工程（代码）
├── core/                           核心基础层（代码，6 个子模块）
├── feature/                        业务特性层（未来，按功能分模块）
├── docs/                           📄 项目文档（总领 + 分文档）
├── assets/                         🎨 静态资源源文件（图标/设计稿/图片/音频等原始素材）
├── data/                           🗄️ 数据类文件（数据库/配置/样本数据等）
├── references/                     参考源码与第三方样例（只读，供借鉴）
├── buildSrc/                       （保留）Gradle 自定义插件/脚本
├── gradle/                         Gradle Wrapper 与版本目录
├── .workbuddy/                     WorkBuddy 项目记忆与配置（勿删）
├── README.md                       项目总入口（指向 docs/INDEX.md）
├── build.gradle.kts                根构建脚本
├── settings.gradle.kts             模块拓扑声明
├── gradle.properties               全局 Gradle 配置
└── .gitignore                      忽略规则
```

---

## 二、各目录职责与规范

### 1. `docs/` —— 项目文档

**原则：一个总领 + 合理分文档，绝不在 README 里堆所有内容。**

```
docs/
├── INDEX.md                    总领索引：导航所有分文档，记录文档清单
├── architecture/               架构类文档
│   ├── overview.md             总体架构设计
│   └── module-map.md           模块依赖关系图
├── keepalive/                  保活专题
│   ├── design.md               保活方案设计（借鉴归纳）
│   └── integration.md          保活模块集成说明
├── dev/                        开发规范
│   ├── coding-style.md         代码规范
│   ├── git-workflow.md         Git 协作规范
│   └── commit-convention.md    提交信息规范
└── api/                        接口文档（未来）
```

**规则：**
- 每新增一个模块/功能，在 `docs/` 对应分类下新建独立文档，**不追加到 README**。
- `docs/INDEX.md` 必须同步更新，是文档的唯一导航入口。
- 文档命名用 `kebab-case.md`（小写连字符）。

### 2. `assets/` —— 静态资源源文件

**原则：存放「原始素材」，不放「编译产物」。**

```
assets/
├── icons/                      图标源文件（PNG/SVG）
│   └── 沟通记录.png             应用图标源图
├── images/                     图片素材
├── audio/                      音频素材
├── fonts/                      字体文件
└── design/                     设计稿（Figma/Sketch 导出）
```

**规则：**
- 图标源图放在 `assets/icons/`；编译后的各分辨率 mipmap 由构建/脚本生成到 `app/src/main/res/`，**不手工往 res 塞源图**。
- 生成脚本放在 `tools/`（见下），保证「源图 → 产物」可重复生成。

### 3. `data/` —— 数据类文件

**原则：区分「代码内资源」与「外部数据文件」。**

```
data/
├── sqlite/                     数据库文件 / SQL 迁移脚本（.sql / .db）
├── config/                     配置文件（非代码内配置，如服务器地址清单）
├── samples/                    样本/测试数据（JSON/CSV 等）
└── schema/                     数据模型 Schema 定义（Proto/JSON Schema）
```

**规则：**
- 运行时数据库（Room）的 schema 由代码管理，导出文件可放 `data/schema/`。
- 预置的 .db / .sql 文件放 `data/sqlite/`，需在 `assets` 中引用时通过脚本拷贝。

### 4. `references/` —— 参考源码（只读）

**原则：借鉴用的第三方/样例源码，与业务代码隔离，不参与构建。**

```
references/
├── aurelay-main/               保活参考项目 1
├── SmsForwarder-main/          保活参考项目 2
└── ...
```

**规则：**
- 只读，不修改、不参与 Gradle 构建。
- 用于研究、借鉴，提炼后的代码进入 `core/` 或 `feature/`。
- 当前 `example/` 目录即此用途，**重命名为 `references/`**。

### 5. `tools/` —— 工具与脚本

**原则：可重复执行的生成/检查脚本，与源码分离。**

```
tools/
├── icon/                       图标生成脚本（源图 → mipmap）
└── ...
```

**规则：**
- 任何「从源文件生成产物」的操作都应有对应脚本，避免手工操作不可复现。

### 6. `app/src/main/res/` —— 编译资源（自动生成）

- 只放构建需要的最终资源；图标等由 `tools/` 从 `assets/icons/` 生成。

---

## 三、命名约定

| 类型 | 规则 | 示例 |
|---|---|---|
| 文档 | `kebab-case.md` | `keepalive-design.md` |
| 图片/图标源图 | 中文或 kebab-case | `沟通记录.png` / `app-icon.png` |
| 目录 | `kebab-case` | `core-keepalive/` |
| Kotlin 类 | `PascalCase` | `KeepAliveService` |
| SQL/数据 | `kebab-case.sql` | `migration-v1.sql` |

---

## 四、一句话总纲（供智能体快速理解）

> 代码进 `app`/`core`/`feature`，文档进 `docs`（INDEX 总领分文档），原始素材进 `assets`，外部数据进 `data`，参考源码进 `references`，工具脚本进 `tools`；README 只做入口，不在 README 堆内容。
