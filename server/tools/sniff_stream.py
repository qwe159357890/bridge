"""嗅探真实客户端推流字节：订阅 /api/admin/stream/{device}，落盘并输出统计。

用法：./.venv/Scripts/python.exe tools/sniff_stream.py <device_id> <秒数>
"""
import asyncio
import json
import sys
import time
import urllib.request

import websockets

DEVICE = sys.argv[1]
SECS = int(sys.argv[2]) if len(sys.argv) > 2 else 16
OUT = f"sniff_{int(time.time())}.bin"


def login() -> str:
    req = urllib.request.Request(
        "http://127.0.0.1:15160/api/admin/login",
        data=json.dumps({"username": "admin", "password": "admin123"}).encode(),
        headers={"Content-Type": "application/json"},
    )
    with urllib.request.urlopen(req) as r:
        d = json.load(r)
    return d.get("access_token") or d.get("token", "")


async def main() -> None:
    token = login()
    frames = []
    async with websockets.connect(
        f"ws://127.0.0.1:15160/api/admin/stream/{DEVICE}?token={token}",
        max_size=64 * 1024 * 1024,
    ) as ws:
        print(f"已订阅 {DEVICE}，嗅探 {SECS}s …")
        t0 = time.time()
        try:
            while time.time() - t0 < SECS:
                msg = await asyncio.wait_for(ws.recv(), timeout=SECS)
                if isinstance(msg, bytes):
                    frames.append(msg)
                else:
                    print("文本帧:", str(msg)[:120])
        except TimeoutError:
            pass
    data = b"".join(frames)
    with open(OUT, "wb") as f:
        f.write(data)
    print(f"共 {len(frames)} 帧二进制，{len(data)} 字节 → {OUT}")
    # FLV 结构快检：逐 tag 校验 PrevTagSize
    p, n, bad = 13, 0, 0
    while p + 15 <= len(data):
        size = int.from_bytes(data[p + 1 : p + 4], "big")
        body_end = p + 11 + size
        if body_end + 4 > len(data):
            print(f"tag#{n} 尾部截断（数据未完）@{p}")
            bad += 1
            break
        prev = int.from_bytes(data[body_end : body_end + 4], "big")
        if prev != size + 11:
            print(f"tag#{n} PrevTagSize 不符: {prev} != {size + 11} @byte {p}")
            bad += 1
            if bad >= 6:
                break
        p = body_end + 4
        n += 1
    print(f"共扫描 {n} 个 tag，PrevTagSize 异常 {bad} 处")


asyncio.run(main())
