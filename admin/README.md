# Bridge 管理后台（admin/）

给运维/管理员用的 Web 管理界面，消费 `server/` 的 REST 接口，管理设备、下发命令、查看日志。

## 技术栈

Vue 3 + Vite + Element Plus + TypeScript + Pinia

## 快速开始（本地启动）

管理后台是纯前端项目，不新增后端，只消费 `server/` 的接口。因此需要**先启动后端，再启动前端**。

### 1. 启动后端服务（server/）

```bash
cd server
uv run main.py     # 监听 http://localhost:15160
```

### 2. 启动管理后台前端（admin/）

```bash
cd admin
npm install        # 首次需要安装依赖
npm run dev        # 启动 Vite dev server，/api 代理到 http://localhost:15160
```

### 3. 打开并登录

浏览器打开 http://localhost:5173 ，用默认账号登录：

- 账号：`admin`
- 密码：`admin123`

（账号密码在 `server/config.yaml` 的 `admin` 组里配置，可修改。）

## 构建（生产）

```bash
cd admin
npm run build      # 产出 dist/ 静态文件
```

`dist/` 可部署到任意静态托管（Nginx / 对象存储 / GitHub Pages）。生产环境通过 `VITE_API_BASE` 指向服务端真实地址，需处理 CORS（服务端已可配）。

## 环境变量

| 变量 | 说明 |
|---|---|
| `VITE_API_BASE` | 服务端地址（dev 用代理，prod 填真实地址） |

## 文档

权威设计见 `../docs/admin/design.md`。
