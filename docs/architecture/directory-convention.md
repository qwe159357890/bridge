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
Bridge/                             仓库根目录（monorepo：客户端 + 服务端）
├── app/                            应用壳工程（代码，Android 客户端）
├── core/                           核心基础层（代码，6 个子模块）
├── feature/                        业务特性层（未来，按功能分模块）
├── server/                         🐍 Python 后端服务（独立服务，一等公民，见下）
├── admin/                          🖥️ 管理后台（Web 前端，独立服务，见下）
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

### 7. `server/` —— Python 后端服务（独立服务，一等公民）

**原则：后端是独立服务，与 Android 客户端同仓库管理（monorepo），但代码、依赖、部署完全隔离。分层清晰、可扩展、可测试。**

```
server/
├── main.py                        ★ 唯一启动入口（uv run main.py）
├── README.md                     服务说明（定位、启动方式、协议入口）
├── pyproject.toml                Python 项目元数据与依赖声明（uv 管理）
├── uv.lock                       依赖锁文件（uv 生成，入库）
├── .python-version               固定 Python 版本（3.13）
├── config.yaml                   默认配置（YAML，心跳/端口/鉴权等参数）
├── app/                          应用代码（分层）
│   ├── main.py                   FastAPI 应用工厂（create_app，不直接启动）
│   ├── config.py                 配置加载（config.yaml + 环境变量，Pydantic 校验）
│   ├── api/                      路由包（专门的路由目录，按功能拆模块 + 子路由包）
│   │   ├── router.py             根路由聚合器（统一挂载 ws + v1）
│   │   ├── ws.py                 WebSocket 路由（/ws，握手/鉴权/心跳/读超时）
│   │   └── v1/                   REST v1 子路由包（按功能拆分）
│   │       ├── router.py         v1 版本根路由（/api 前缀）
│   │       ├── devices.py        设备注册/统计
│   │       ├── push.py           消息推送
│   │       ├── connections.py    连接管理（查询/踢下线）
│   │       └── logs.py           日志上传/读取/清除
│   ├── core/                     核心层：连接管理、设备注册（不依赖框架）
│   │   ├── connection_manager.py 连接管理器（唯一连接 + 顶旧 + 映射表 + 推送）
│   │   ├── device_registry.py    设备注册表（token 生成/鉴权，内存实现可替换）
│   │   └── log_store.py          日志存储（内存实现，接口可替换）
│   ├── models/                   领域模型（Connection 等）
│   ├── schemas/                  Pydantic 校验模型（消息信封 + REST 请求/响应）
│   └── services/                 业务逻辑（未来扩展：消息分发等）
├── examples/                     客户端示例（本地联调）
├── tests/                        测试（pytest）
└── docs/                         服务端专属文档（如需，主文档在 docs/server/）
```

**规则：**
- `server/` 与 `app/`（Android）**互不依赖、互不 import**，通过 WebSocket 协议通信。
- 服务端依赖用 **uv** 管理（`pyproject.toml` + `uv.lock`），不污染 Android 的 Gradle 构建。
- **唯一启动方式**：`server/main.py`（`uv run main.py`），内部调 `app.main:create_app()` + uvicorn；host/port 读 `config.yaml` 的 `server` 组；不要在别处直接 uvicorn。
- 配置一律走 `config.yaml`（YAML）+ 环境变量覆盖，经 `config.py` 的 Pydantic 校验；禁止在业务代码里硬编码心跳周期/端口/超时等参数。
- 路由层集中在 `app/api/`，按功能拆模块、可再拆子路由包；`app/main.py` 仅 `include_router(api_router)` 一行，新增路由无需改 main.py。
- 连接管理、心跳、设备注册等核心逻辑放 `core/`，**不依赖 FastAPI**，便于单独测试。
- 服务端文档：总体设计放 `docs/server/`（仓库文档体系内），`server/README.md` 只做快速上手入口。

### 8. `admin/` —— 管理后台（Web 前端，独立服务）

**原则：管理后台是独立的 Web 前端项目，只消费 `server/` 的 REST 接口，不触碰客户端与服务端代码。技术栈 Vue 3 + Vite + Element Plus + TypeScript + Pinia。**

```
admin/
├── package.json                    npm/pnpm 依赖声明
├── vite.config.ts                  构建配置（含 dev 代理到 server）
├── tsconfig.json
├── .env.development                本地环境变量（VITE_API_BASE=http://localhost:15160）
├── .env.production                 生产环境变量
├── index.html
├── src/
│   ├── main.ts                     入口
│   ├── App.vue
│   ├── router/                     路由（含登录守卫）
│   ├── stores/                     Pinia 状态（登录态、设备数据）
│   ├── api/                        对接服务端 REST（request 封装 + 各模块接口）
│   ├── layout/                     整体布局（侧边栏 + 顶栏）
│   ├── views/                      页面（login/dashboard/device/command/log/push/connection）
│   └── components/                 通用组件
```

**规则：**
- 与 `server/`、`app/` 平级，同仓库 monorepo 管理，代码/依赖/部署完全隔离。
- 不新增后端语言——复用现有 FastAPI 服务的 REST 接口（`/api/*`）。
- 管理后台鉴权用「简单管理员密钥」方案（服务端配置管理员账号密码，登录换 token，请求带 token）。
- 权威设计文档：`docs/admin/design.md`。

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

> 代码进 `app`/`core`/`feature`（客户端）与 `server/`（服务端），文档进 `docs`（INDEX 总领分文档），原始素材进 `assets`，外部数据进 `data`，参考源码进 `references`，工具脚本进 `tools`；README 只做入口，不在 README 堆内容。
