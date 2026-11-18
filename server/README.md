# Bridge Server

Bridge 的 Python 服务端（FastAPI + WebSocket）。承载 Android 客户端的 WebSocket 长连接，负责连接管理、消息推送、设备鉴权。

> **权威设计文档见**：[`docs/server/design.md`](../docs/server/design.md)。
> 本文档只做快速上手入口，不在 README 堆内容。

## 定位

- 客户端：Bridge Android App（`app/` 工程）
- 服务端：本目录（`server/`），通过 WebSocket + REST 通信
- 已实现：连接管理（心跳/断线/重连/唯一连接）+ 消息推送 + 设备鉴权 + REST 管理接口

## 目录结构

```
server/
├── main.py                # ★ 唯一启动入口（uv run main.py）
├── app/
│   ├── main.py            # FastAPI 应用工厂 + 依赖注入（不直接启动）
│   ├── config.py          # 配置加载（config.yaml + 环境变量覆盖）
│   ├── api/               # 路由包（专门的路由目录，按功能拆模块）
│   │   ├── router.py      #   根路由聚合器（统一挂载 ws + v1）
│   │   ├── ws.py          #   WebSocket 路由（握手/鉴权/心跳/读超时）
│   │   └── v1/            #   REST v1 子路由包（按功能拆分）
│   │       ├── router.py  #     v1 版本根路由（/api 前缀）
│   │       ├── devices.py #     设备注册/统计
│   │       ├── push.py    #     消息推送
│   │       ├── connections.py # 连接管理（查询/踢下线）
│   │       └── logs.py    #     日志上传/读取/清除
│   ├── core/              # 连接管理器 / 设备注册表 / 日志存储（不依赖框架，可单测）
│   ├── models/            # 领域模型（Connection）
│   ├── schemas/           # Pydantic 校验模型（消息信封 + REST 模型）
│   └── services/          # 业务逻辑（未来）
├── examples/ws_client.py  # 客户端示例（鉴权/心跳/重连/接收推送）
├── tests/                 # 单元测试
├── config.yaml            # 默认配置
├── pyproject.toml         # 依赖与构建（uv 管理）
├── uv.lock                # 依赖锁文件
└── docs/
```

## 快速开始

依赖用 **uv** 管理（`pyproject.toml` + `uv.lock`）。

```bash
cd server
uv sync            # 安装运行 + 开发依赖
uv run main.py     # ★ 唯一启动方式（host/port 由 config.yaml 的 server 配置组决定）
```

运行测试：

```bash
uv run pytest -q
```

本地联调（另开终端跑客户端示例）：

```bash
# 不鉴权模式（默认 auth.enabled=false）
uv run python examples/ws_client.py --device-id dev-001

# 鉴权模式：先注册拿 token，再带 token 连接
uv run python examples/ws_client.py --device-id dev-001 --register
```

REST 接口（开启服务后可访问 `/docs` 查看 Swagger）：

```bash
curl -X POST localhost:15160/api/devices/register -H 'Content-Type: application/json' -d '{"device_id":"dev-1"}'
curl -X POST localhost:15160/api/push -H 'Content-Type: application/json' -d '{"device_ids":["dev-1"],"payload":{"msg":"hi"}}'
curl localhost:15160/api/connections
curl -X POST localhost:15160/api/logs -H 'Content-Type: application/json' -d '{"device_id":"dev-1","content":"诊断日志"}'
curl localhost:15160/api/logs/dev-1
```

详见 `docs/server/design.md`。
