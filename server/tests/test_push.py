"""消息推送能力单元测试（send_to_device / broadcast）。"""

import pytest

from app.core.connection_manager import ConnectionManager
from app.models.connection import Connection


@pytest.fixture
def manager():
    return ConnectionManager()


@pytest.mark.asyncio
async def test_send_to_device(manager):
    conn = Connection()
    await manager.register("dev-1", conn)

    sent = []
    conn.set_send_handler(lambda raw: sent.append(raw) or _noop())

    ok = await manager.send_to_device("dev-1", '{"type":"message"}')
    assert ok is True
    assert sent == ['{"type":"message"}']


@pytest.mark.asyncio
async def test_send_to_unknown_device(manager):
    ok = await manager.send_to_device("ghost", '{"type":"message"}')
    assert ok is False


@pytest.mark.asyncio
async def test_broadcast(manager):
    c1, c2 = Connection(), Connection()
    await manager.register("dev-1", c1)
    await manager.register("dev-2", c2)

    received = []
    c1.set_send_handler(lambda raw: received.append("dev-1") or _noop())
    c2.set_send_handler(lambda raw: received.append("dev-2") or _noop())

    count = await manager.broadcast('{"type":"message"}')
    assert count == 2
    assert sorted(received) == ["dev-1", "dev-2"]


@pytest.mark.asyncio
async def test_list_connections(manager):
    await manager.register("dev-1", Connection())
    await manager.register("dev-2", Connection())
    conns = manager.list_connections()
    assert len(conns) == 2
    assert {c["device_id"] for c in conns} == {"dev-1", "dev-2"}


async def _noop():
    return None
