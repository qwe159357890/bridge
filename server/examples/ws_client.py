#!/usr/bin/env python3
"""最小 WebSocket 客户端示例（本地联调用）。

模拟 Bridge Android 客户端的行为：
  注册设备（可选）→ 握手（hello + token）→ 心跳（ping）→ 接收推送 → 断线重连。

用于验证服务端的完整能力：鉴权、唯一连接、顶旧、心跳、读超时、消息推送。

用法：
    # 不鉴权（auth.enabled=false 时）
    python examples/ws_client.py --device-id dev-001

    # 先注册拿 token，再带 token 连接
    python examples/ws_client.py --device-id dev-001 --register
"""

from __future__ import annotations

import argparse
import asyncio
import json
import random
import sys
import time
import uuid

try:
    import websockets
except ImportError:
    raise SystemExit("缺少 websockets 库：pip install websockets")

try:
    import httpx
except ImportError:
    httpx = None


async def register_device(base_url: str, device_id: str) -> str:
    """调用 REST 接口注册设备，返回 token。"""
    if httpx is None:
        raise SystemExit("缺少 httpx 库：pip install httpx")
    async with httpx.AsyncClient() as client:
        resp = await client.post(f"{base_url}/api/devices/register", json={"device_id": device_id})
        resp.raise_for_status()
        data = resp.json()
        print(f"[{device_id}] 注册成功，token={data['token'][:16]}...")
        return data["token"]


async def run(
    device_id: str,
    ws_url: str,
    base_url: str,
    token: str | None,
    heartbeat_interval: float,
) -> None:
    """客户端主逻辑：连接 → 心跳循环 → 接收推送 → 断线重连。"""
    reconnect_base = 1.0
    reconnect_max = 60.0
    attempt = 0

    while True:
        try:
            async with websockets.connect(ws_url) as ws:
                # 握手
                hello_data = {"device_id": device_id}
                if token:
                    hello_data["token"] = token
                await ws.send(json.dumps({
                    "type": "hello",
                    "ts": int(time.time() * 1000),
                    "data": hello_data,
                }))
                print(f"[{device_id}] 已连接，发送 hello")

                attempt = 0  # 连接成功，重置退避

                # 心跳 + 接收消息循环
                async def heartbeat():
                    while True:
                        await asyncio.sleep(heartbeat_interval)
                        await ws.send(json.dumps({
                            "type": "ping",
                            "ts": int(time.time() * 1000),
                        }))

                async def receiver():
                    while True:
                        reply = await ws.recv()
                        data = json.loads(reply)
                        print(f"[{device_id}] 收到 {data['type']}: {data.get('data', '')}")
                        if data["type"] == "close":
                            print(f"[{device_id}] 被服务端关闭，退出")
                            return False
                    return True

                # 并发跑心跳发送与接收
                hb_task = asyncio.create_task(heartbeat())
                recv_task = asyncio.create_task(receiver())
                done, pending = await asyncio.wait(
                    {hb_task, recv_task}, return_when=asyncio.FIRST_COMPLETED
                )
                for t in pending:
                    t.cancel()
                # 若 receiver 返回 False（收到 close），退出重连循环
                if recv_task in done:
                    keep_going = recv_task.result()
                    if not keep_going:
                        return

        except (websockets.ConnectionClosed, OSError) as e:
            print(f"[{device_id}] 连接断开：{type(e).__name__}")

        # 指数退避 + 随机抖动
        delay = min(reconnect_base * (2 ** attempt), reconnect_max)
        delay *= 1 + random.uniform(-0.2, 0.2)
        attempt += 1
        print(f"[{device_id}] {delay:.2f}s 后重连（第 {attempt} 次）")
        await asyncio.sleep(delay)


def main() -> None:
    parser = argparse.ArgumentParser(description="Bridge WS 客户端示例")
    parser.add_argument("--device-id", default=f"dev-{uuid.uuid4().hex[:8]}",
                        help="设备标识（默认随机生成）")
    parser.add_argument("--ws-url", default="ws://127.0.0.1:15160/ws")
    parser.add_argument("--base-url", default="http://127.0.0.1:15160",
                        help="REST 基础地址（用于注册）")
    parser.add_argument("--heartbeat", type=float, default=20.0,
                        help="心跳周期（秒，默认 20）")
    parser.add_argument("--register", action="store_true",
                        help="先注册设备获取 token，再带 token 连接")
    parser.add_argument("--token", default=None,
                        help="直接指定 token（跳过注册）")
    args = parser.parse_args()

    token = args.token
    if args.register:
        token = asyncio.run(register_device(args.base_url, args.device_id))

    try:
        asyncio.run(run(args.device_id, args.ws_url, args.base_url, token, args.heartbeat))
    except KeyboardInterrupt:
        print("已退出")


if __name__ == "__main__":
    main()
