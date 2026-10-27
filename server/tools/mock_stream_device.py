"""模拟设备推流（复刻客户端 MediaCaptureService 流式录像行为）。

用法：
    ./.venv/Scripts/python.exe tools/mock_stream_device.py [ts文件] [分片字节] [device_id]

行为：注册 mock-stream-1 设备 → WS /ws → hello → 把 TS 文件按指定字节
（默认 512KB，刻意不对齐 188 包边界，复刻客户端任意边界切割）每 500ms
以 base64 media_stream 推流，最后发 is_end。总时长 = 分片数 × 0.5s。
"""
import asyncio
import base64
import json
import sys

import httpx
import websockets

HTTP = "http://127.0.0.1:15999"
WS = "ws://127.0.0.1:15999/ws"
DEVICE = "mock-stream-1"


async def main() -> None:
    ts_path = sys.argv[1] if len(sys.argv) > 1 else "data/media/f2c38ebee251a196/20261027_190511_981776_video.ts"
    chunk_size = int(sys.argv[2]) if len(sys.argv) > 2 else 512 * 1024
    device_id = sys.argv[3] if len(sys.argv) > 3 else DEVICE
    data = open(ts_path, "rb").read()
    chunks = [data[i : i + chunk_size] for i in range(0, len(data), chunk_size)]
    print(f"文件 {len(data)} 字节 → {len(chunks)} 片，预计 {len(chunks) * 0.5:.1f}s")

    async with httpx.AsyncClient(timeout=10, trust_env=False) as http:
        r = await http.post(f"{HTTP}/api/devices/register", json={"device_id": device_id, "device_name": "MockStream"})
        r.raise_for_status()
        token = r.json()["token"]
    print("注册成功，token 前 8 位:", token[:8])

    async with websockets.connect(WS) as ws:
        await ws.send(json.dumps({"type": "hello", "data": {"device_id": device_id, "token": token}}))
        await asyncio.sleep(6.0)  # 给浏览器留出先订阅的时间
        # 起始帧（info）
        await ws.send(json.dumps({
            "type": "message",
            "data": {"event": "media_stream", "kind": "video", "chunk_index": 0, "data": "",
                     "info": {"format": "mpeg2ts", "video": "h264", "audio": "aac",
                              "width": 1280, "height": 720, "duration_sec": len(chunks) // 2}},
        }))
        for i, chunk in enumerate(chunks, start=1):
            await ws.send(json.dumps({
                "type": "message",
                "data": {"event": "media_stream", "kind": "video", "chunk_index": i,
                         "data": base64.b64encode(chunk).decode(), "bytes": len(chunk)},
            }))
            await asyncio.sleep(0.5)
        await ws.send(json.dumps({
            "type": "message",
            "data": {"event": "media_stream", "kind": "video", "chunk_index": len(chunks) + 1,
                     "data": "", "is_end": True, "total_bytes": len(data)},
        }))
        print("推流完成（is_end 已发）")


if __name__ == "__main__":
    asyncio.run(main())
