"""Bridge Server 唯一启动入口。

用法（在 server/ 目录下）：

    uv run main.py

等价于用 uvicorn 启动 ``app.main:app``，host / port 等由 ``config.yaml`` 的
``server`` 配置组决定（也可用 ``BRIDGE_SERVER_HOST`` / ``BRIDGE_SERVER_PORT``
等环境变量覆盖）。

这是启动后端的**唯一**方式，不要在别处直接 ``uvicorn`` 拉起服务。
"""

from __future__ import annotations

import uvicorn

from app.config import get_settings


def main() -> None:
    """启动服务。"""
    settings = get_settings()
    uvicorn.run(
        "app.main:app",
        host=settings.server.host,
        port=settings.server.port,
        # 生产可开 reload；默认关闭，避免开发误开
        reload=False,
    )


if __name__ == "__main__":
    main()
