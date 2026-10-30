# 音频中继「正在接收但无声」根因排查报告

> 排查日期：2026-10-29
> 结论版本：v1（基于真实服务器日志 center_server_2026-10-29.log / err.log / out.log）
> 服务器：Ubuntu 86.74.128.24，路径 `/home/qwe159357/Programs/wxhelper/center-server/`

---

## 一、现象

手机端切换 WiFi / 移动数据后，界面一直显示「正在接收」，但没有声音。手机端（接收方 R）在服务器日志里**反复正常接入**，但 PC 端（发送方 S）在某个时间点之后**彻底消失**，两端再也无法「到齐」，中转链路永远建立不起来。

## 二、结论（一句话）

**问题不在「PC 端不重连」，而在服务器端 `aurelay_relay_ws` 的清理逻辑无超时保护 + websockets 库 keepalive 断言崩溃，导致中转房间残留「僵尸发送方(PC)连接」，手机永远等不到配对就绪。**

（修正上一轮「PC 端 `_recv_thread` 忽略 409 不重连」的判断：PC 端其实一直在重连，只是被服务器端的僵尸连接卡死，每次重连都走不到注册成功那一步。）

## 三、关键时间线（铁证）

来源：`center_server_2026-10-29.log`，设备 `b1ac63ea-db35-48e3-9c4e-b0692846b853`。

| 时间 | 事件 | 说明 |
|---|---|---|
| 17:44~17:54 | 手机/PC 多次「被取代→重连→两端就绪」 | 自愈链路**原本正常**，说明 v0.1.83 的改动方向是对的 |
| 17:54:21.357 | 手机 R 被新连接取代 | 新 R2 顶替旧 R1 |
| 17:54:22.100 | 发送方 PC 被新连接取代 | 新 S2 顶替旧 S1 |
| 17:54:22.101 | 发送方 PC 已接入 | S2 注册 |
| 17:54:22.102 | **两端已到齐，中转链路就绪** | 注意：此时注册的是 S2 + 旧 R1 |
| 17:54:22.102 | 接收方(手机)已接入 | 新 R2 才注册（比「就绪」还晚！） |
| **17:55:00.977** | 接收方(手机)连接结束（异常为空） | 手机正常关闭连接 |
| **17:55 ~ 18:02** | 服务器 aurelay 日志**完全空白 7 分钟** | `finally` 清理块**卡死** |
| **18:02:06** | 才打印「接收方(手机)已下线」 | finally 卡了 7 分钟后才恢复 |
| 18:02 之后 | 只有「手机已接入」，**PC 发送方彻底消失** | 房间被僵尸 S 占位 |

## 四、三个核心证据

### 证据 ①：`finally` 清理块卡死 7 分钟

`aurelay.py` 第 592 行打印「连接结束」后，第 599 行的「已下线」本该紧跟执行，实际延迟了 **7 分钟**（17:55→18:02）。

源码（`api/routes/aurelay.py`）`finally` 块：

```python
finally:
    room = _RELAY_ROOMS.get(device_id)
    if room and room.get(role_key) is websocket:
        room.pop(role_key, None)
        logger.info(f"...已下线")                 # 599 行
        if role_key == "R":
            stale_sender = room.get("S")
            if stale_sender is not None:
                await stale_sender.send_text(...)  # ← 卡死点 A
                await stale_sender.close()         # ← 卡死点 B
                room.pop("S", None)
```

当 PC 的 S 连接因网络切换处于「TCP 半开」状态时，`send_text` / `close` 会**阻塞挂起**（不是抛异常），把整个协程钉死，房间里的僵尸 S 无法清除，后续「已下线」「清理 PC」日志全部延迟。

### 证据 ②：websockets 断言崩溃（err.log）

```
ERROR: keepalive ping failed
  → legacy/protocol.py:308 _drain_helper
  → assert waiter is None or waiter.cancelled()
AssertionError

ERROR: data transfer failed
  → 同样的 AssertionError（write_close_frame 路径）
```

服务器 websockets **16.0** + uvicorn **0.46**（走 websockets legacy 兼容层），`keepalive_ping` 与 `close()` 并发时触发断言失败，**uvicorn 的 WebSocket 处理协程被异常终止**，进一步加剧房间状态腐坏。

### 证据 ③：PC 端其实一直在重连（out.log / err.log）

```
86.74.128.30:xxxx - "WebSocket /api/aurelay/relay" [accepted]
INFO: connection open
INFO: connection closed    ← 反复出现，但从未走到「发送方(PC)已接入」
```

PC（发送方 86.74.128.30）一直在尝试重连，但每次都停在「顶替旧僵尸 S → `await old.close()` 卡死」这一步，永远走不到注册成功。

## 五、完整根因链（闭合）

1. 手机切网络 → R 连接正常断开/重接入。
2. 服务器 `finally` 清理时，对半死的 S 连接 `send_text`/`close` **无超时** → 卡死或断言崩溃。
3. 卡死导致 `room.pop("S")` 未执行 → 房间残留**僵尸 S 连接**。
4. 手机 R 后续每次重接入，服务器都认为「房间里已有 S」，走「R 重接入清 S」逻辑 → 又去 close 那个僵尸 S → 又卡死 → **死循环**。
5. PC 端一直在重连，但每次新 S 要「顶替旧僵尸 S」→ `await old.close()` 卡死 → 新 S 永远注册不了 → PC 端 `_connect_once` 等不到 `ready`，8 秒超时返回失败 → 上层退避重试 → 再次循环。
6. 最终表现：手机一直「正在接收」，PC 永远连不上，两端永远凑不齐 → 无声。

## 六、修复建议（按优先级）

### P0：给 `finally` 里所有 `send_text` / `close` 加超时

```python
import asyncio

async def _safe_close(ws, timeout=3.0):
    try:
        await asyncio.wait_for(ws.close(), timeout=timeout)
    except Exception:
        pass
```

清理逻辑一律走 `_safe_close`，保证任何情况下 finally 都不会卡死，房间状态能及时收敛。这是最直接、最关键的一处。

### P1：关闭 uvicorn 层 keepalive ping，改由应用层心跳负责

应用层已有 `ping`/`pong` 心跳。uvicorn 默认 `ws_ping_interval=20s` 触发的 keepalive ping 与 close 并发会踩中 websockets 16.0 legacy 层的断言 bug。在 `uvicorn.run` 里显式关闭：

```python
uvicorn.run(app, ..., ws_ping_interval=0.0, ws_ping_timeout=None)
```

或升级 uvicorn/websockets 到修复了该断言的版本。

### P2：PC 端 `_recv_thread` 补上 409 重连处理

`relay_client.py` 的 `_start_recv` 里，`recv()` 收到 409 错误帧时当前只是 `except` 后空转，未触发上层重连。应识别 409 并回调重连（上一轮已定位的次要问题，顺手一起修）。

## 七、涉及文件

| 位置 | 文件 | 说明 |
|---|---|---|
| 服务器 | `api/routes/aurelay.py` | 中转端点，finally 清理逻辑（卡死点） |
| 服务器 | `main.py` | uvicorn.run 配置（可加 ws_ping_interval=0） |
| PC 端 | `references/pc-client/gui/widgets/Aurelay声音推送/relay_client.py` | 发送方重连逻辑（_recv_thread 缺 409 处理） |

## 八、环境信息

- websockets 16.0，uvicorn 0.46.0，fastapi 0.136.1（服务器 .venv）
- 服务器进程 PID 785，自 06:01 起未重启
- 设备：OnePlus LE2120（手机，R 端），PC 端发送方 IP 86.74.128.30
