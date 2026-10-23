# Bridge 管理后台设计

> 权威设计文档。本文档定义管理后台的定位、技术选型、目录结构、功能清单、接口对接与鉴权方案。
> 管理后台是「给人用的可视化界面」，把 `server/` 已实现的 REST 管理接口做成可点、可看、可操作的管理系统。

---

## 一、定位与边界

### 1.1 定位

Bridge 系统的三个组成部分：

```
Android App  ──WebSocket──▶  Python 服务端  ◀──REST 接口──  管理后台（Web）
```

- **Android App**：设备端，常驻保活、心跳、收命令、上传日志。
- **Python 服务端**：连接管理 + 设备注册鉴权 + 推送/命令/日志，已有完整 REST 接口。
- **管理后台**：运维/管理员在浏览器里使用的界面，**只消费服务端 REST 接口**。

### 1.2 边界（关键）

- 管理后台**不新增后端**，复用现有 FastAPI 服务的 `/api/*` 接口。
- 管理后台**不触碰**客户端（`app/` `core/` `feature/`）与服务端（`server/`）代码，是独立前端工程。
- 管理后台与服务端通过 HTTP/JSON 通信（未来可加管理侧 WebSocket 做实时回执推送）。

---

## 二、技术选型

| 维度 | 选择 | 理由 |
|---|---|---|
| 语言 | **TypeScript** | 类型安全，配合 Element Plus 有完整类型提示 |
| 框架 | **Vue 3**（Composition API） | 国内管理后台事实标准，中文文档最全 |
| 构建 | **Vite** | 冷启动快、配置简单 |
| UI 组件库 | **Element Plus** | 表格/表单/弹窗/标签齐全，运维界面开发效率最高 |
| 状态管理 | **Pinia** | Vue3 官方推荐，轻量 |
| 路由 | **Vue Router** | 官方路由，含登录守卫 |
| HTTP | **axios** | 拦截器统一处理 token / 错误 |
| 包管理 | **pnpm**（或 npm） | 快速、节省磁盘 |

### 2.1 选型依据（对比已调研的开源方案）

| 方案 | 技术栈 | 结论 |
|---|---|---|
| vue-pure-admin | Vue3 + Element Plus | ✅ 借鉴其代码规范与目录组织，封装克制 |
| Vue Vben Admin | Vue3 + Ant Design Vue | ❌ 抽象层过深、偏重型，规模不匹配 |
| Soybean Admin | Vue3 + Naive UI | 可选，生态略小 |
| JeecgBoot / Gin-Vue-Admin | 自带 Java/Go 后端 | ❌ 已有 Python 服务端，无需额外后端 |

> 结论：**不直接 fork 整套模板**（会带入大量用不到的代码），而是按 vue-pure-admin 的「克制封装」思路，从零搭一个贴合 Bridge 接口的精简骨架。

---

## 三、目录结构

```
admin/
├── package.json
├── vite.config.ts               # dev 代理 /api -> http://localhost:15160
├── tsconfig.json
├── .env.development             # VITE_API_BASE=
├── .env.production
├── index.html
└── src/
    ├── main.ts
    ├── App.vue
    ├── router/index.ts          # 路由 + 登录守卫
    ├── stores/
    │   ├── auth.ts              # 登录态（token 持久化）
    │   └── device.ts            # 设备数据缓存
    ├── api/
    │   ├── request.ts           # axios 实例 + 拦截器（注入 token、统一错误）
    │   ├── auth.ts              # 登录接口
    │   ├── devices.ts           # GET/POST /api/devices*
    │   ├── commands.ts          # POST/GET /api/commands*
    │   ├── logs.ts              # GET/DELETE /api/logs*
    │   ├── push.ts              # POST /api/push
    │   ├── connections.ts       # GET /api/connections
    │   └── health.ts            # GET /health
    ├── layout/index.vue         # 侧边栏 + 顶栏 + 内容区
    ├── views/
    │   ├── login/index.vue      # 登录页
    │   ├── dashboard/index.vue  # 仪表盘
    │   ├── device/index.vue     # 设备管理
    │   ├── command/index.vue    # 命令下发 + 历史
    │   ├── log/index.vue        # 日志查看
    │   ├── push/index.vue       # 消息推送
    │   └── connection/index.vue # 连接监控
    └── components/
        ├── DeviceStatusTag.vue  # 在线/离线标签
        └── DevicePicker.vue     # 设备选择器（下拉）
```

---

## 四、功能清单（对接现有接口）

### 4.1 页面与接口映射

| 页面 | 功能 | 对接接口 |
|---|---|---|
| **登录** | 管理员账号密码登录，token 持久化，路由守卫 | `POST /api/admin/login`（需补） |
| **仪表盘** | 在线设备数、注册总数、运行时长、连接趋势 | `GET /health` + `GET /api/devices` |
| **设备管理** | 设备列表（昵称/ID/在线状态/注册时间）、搜索、踢下线 | `GET /api/devices`、`POST /api/devices/{id}/disconnect` |
| **命令下发** | 挑设备下发命令、命令历史、回执状态 | `POST /api/commands`、`GET /api/commands`、`GET /api/commands/{id}` |
| **日志查看** | 列出有日志设备、查看某设备日志、清除 | `GET /api/logs`、`GET /api/logs/{id}`、`DELETE /api/logs/{id}` |
| **消息推送** | 向一个/多个设备推送消息 | `POST /api/push` |
| **连接监控** | 实时连接列表、主动断开 | `GET /api/connections` |

### 4.2 页面详细设计

**仪表盘（Dashboard）**
- 顶部 3~4 个统计卡片：在线设备数、已注册设备、活跃连接数、运行时长。
- 设备在线率图表（可选，ECharts）。

**设备管理（Device）**
- 表格列：设备昵称、device_id、在线状态（标签）、注册时间、操作。
- 操作：下发命令（跳转命令页并预选设备）、踢下线、查看日志（跳转日志页）。
- 支持按昵称/ID/状态筛选。

**命令下发（Command）**
- 命令表单：选择设备（仅在线设备可选）+ 命令类型（当前仅 `upload_log`）。
- 命令历史表格：command_id、设备、命令、状态（pending/success/failed）、时间。
- 点击查看单条命令的回执结果（`GET /api/commands/{id}`）。

**日志查看（Log）**
- 左侧/顶部：有日志的设备列表。
- 选中设备后：展示该设备日志列表（内容、app_version、时间）。
- 操作：清除该设备日志。

**消息推送（Push）**
- 多选设备 + 自定义 payload（JSON 文本）。
- 展示送达/失败统计。

**连接监控（Connection）**
- 活跃连接列表（device_id、连接时间）。
- 操作：主动断开。

---

## 五、鉴权方案（简单管理员密钥）

### 5.1 现状问题

现有 `auth.enabled` 只控制**设备**鉴权，`/api/devices`、`/api/commands` 等**管理接口完全无鉴权**，任何人可调。管理后台上线前必须补齐。

### 5.2 方案

服务端新增「管理员」概念（静态配置，不引入数据库）：

- `config.yaml` 新增 `admin` 组：`username` / `password` / `token_ttl`。
- `POST /api/admin/login`：校验用户名密码，返回一个 admin token。
- 管理接口统一校验请求头 `Authorization: Bearer <admin_token>`（或 `X-Admin-Key`）。

### 5.3 需服务端补齐的改动（管理后台之外的配套）

1. `config.yaml` 增加 `admin` 配置组。✅ 已实现
2. 新增 `core/admin_auth.py`（生成/校验 admin token，内存实现）。✅ 已实现
3. 新增 `api/v1/admin.py`（`POST /api/admin/login`）。✅ 已实现
4. 给 `/api/devices` `/api/commands` `/api/logs` `/api/push` `/api/connections` 加依赖校验（`Depends` 注入 admin token 校验）。✅ 已实现
5. （可选）管理侧 WebSocket 通道，命令回执实时推送给管理后台。✅ 已实现（用 WebSocket `WS /api/admin/ws`）

---

## 六、开发与环境

### 6.1 本地启动

管理后台是纯前端项目，不新增后端，只消费 `server/` 接口，需**先启动后端、再启动前端**：

**① 启动后端（server/）：**

```
cd server
uv run main.py     # 监听 http://localhost:15160
```

**② 启动前端（admin/）：**

```
cd admin
npm install        # 首次需装依赖
npm run dev        # Vite dev server，/api 代理到 http://localhost:15160
```

**③ 打开并登录：** 浏览器访问 http://localhost:5173 ，默认账号 `admin` / `admin123`（见 `server/config.yaml` 的 `admin` 组）。

### 6.2 环境变量

| 变量 | 说明 |
|---|---|
| `VITE_API_BASE` | 服务端地址（dev 用代理，prod 填真实地址） |

### 6.3 构建与部署

```
npm run build        # 产出 dist/ 静态文件
```

- 可部署到任意静态托管（Nginx / 对象存储 / GitHub Pages）。
- 生产环境通过 `VITE_API_BASE` 指向服务端真实地址，需处理 CORS（服务端已可配）。

---

## 七、实施顺序

1. **补服务端管理鉴权**（安全地基，先做）✅ 已完成
2. **搭 admin 骨架**（Vue3 + Vite + Element Plus + 路由 + axios + 登录页）✅ 已完成
3. **实现核心页面**（仪表盘 → 设备管理 → 命令下发 → 日志查看）✅ 已完成
4. **增强**（实时回执推送、消息推送、连接监控、打包部署）✅ 已完成

## 七·补充：管理侧实时推送（WebSocket）

命令回执的「实时通知管理侧」用 **WebSocket** 实现，为后续复杂功能与双向交互预留能力：

- 服务端 `core/admin_event_bus.py`：内存事件总线，`publish` 广播给所有已连接的管理后台。
- 服务端 `WS /api/admin/ws`：管理侧实时事件通道，连接后实时接收 `command_result` 事件。
  - 鉴权：优先取 `Authorization: Bearer` header；浏览器端 WebSocket 无法自定义 header，故也兼容 query 参数 `?token=`。
- `ws.py` 收到命令回执时，除写入 CommandStore 外，同步 `publish` 事件。
- 前端命令页用原生 `WebSocket` 连接，收到回执即刷新列表 + 弹提示；断线自动重连（3s 退避）。

## 八、遗留 TODO

- ~~命令回执「实时通知管理侧」~~ ✅ 已通过 WebSocket 实现。
- 管理后台权限分级（多管理员/角色）：当前单管理员，后续可扩展 RBAC。
- 设备在线率趋势图：依赖服务端补充连接时序数据。
- 前端构建 chunk 较大（Element Plus 全量引入），后续可改按需引入优化体积。
