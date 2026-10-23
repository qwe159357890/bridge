"""管理侧事件总线测试：连接、广播、断开。"""

import asyncio

import pytest

from app.core.admin_event_bus import AdminEventBus


class _FakeWS:
    """模拟 WebSocket 连接，记录发送的消息。"""

    def __init__(self):
        self.sent: list[str] = []

    async def send_text(self, text: str) -> None:
        self.sent.append(text)


def test_publish_to_connected():
    bus = AdminEventBus()
    ws = _FakeWS()

    async def _run():
        await bus.connect(ws)
        await bus.publish("command_result", {"command_id": "c1", "success": True})
        await bus.disconnect(ws)
        return ws.sent

    sent = asyncio.run(_run())
    assert len(sent) == 1
    assert '"type": "command_result"' in sent[0]
    assert '"success": true' in sent[0]


def test_disconnect_stops_receiving():
    bus = AdminEventBus()
    ws = _FakeWS()

    async def _run():
        await bus.connect(ws)
        await bus.disconnect(ws)
        await bus.publish("command_result", {"command_id": "c1"})
        return ws.sent

    # 已断开，不应再收到事件
    assert asyncio.run(_run()) == []


def test_multiple_connections_all_receive():
    bus = AdminEventBus()
    ws1 = _FakeWS()
    ws2 = _FakeWS()

    async def _run():
        await bus.connect(ws1)
        await bus.connect(ws2)
        await bus.publish("command_result", {"command_id": "c1"})
        await bus.disconnect(ws1)
        await bus.disconnect(ws2)
        return ws1.sent, ws2.sent

    s1, s2 = asyncio.run(_run())
    assert s1 == s2
    assert len(s1) == 1
    assert '"command_id": "c1"' in s1[0]


def test_active_count():
    bus = AdminEventBus()

    async def _run():
        ws = _FakeWS()
        await bus.connect(ws)
        count_with_one = bus.active_count
        await bus.disconnect(ws)
        return count_with_one, bus.active_count

    with_one, after = asyncio.run(_run())
    assert with_one == 1
    assert after == 0
