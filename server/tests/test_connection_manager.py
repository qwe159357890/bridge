"""连接管理器单元测试：唯一连接、顶旧连接、并发握手、连接数上限。"""

import asyncio

import pytest

from app.core.connection_manager import ConnectionLimitError, ConnectionManager
from app.models.connection import Connection, ConnectionState


@pytest.fixture
def manager():
    return ConnectionManager()


@pytest.mark.asyncio
async def test_register_first_connection(manager):
    conn = Connection()
    replaced = await manager.register("dev-1", conn)
    assert replaced is None
    assert conn.state == ConnectionState.ACTIVE
    assert conn.device_id == "dev-1"
    assert manager.active_count == 1


@pytest.mark.asyncio
async def test_replace_old_connection(manager):
    old = Connection()
    await manager.register("dev-1", old)

    new = Connection()
    replaced = await manager.register("dev-1", new)

    # 顶旧：返回被顶掉的旧连接，旧连接标记 CLOSED
    assert replaced is old
    assert old.state == ConnectionState.CLOSED
    assert new.state == ConnectionState.ACTIVE
    # 映射表里只剩新连接
    assert manager.get("dev-1") is new
    assert manager.active_count == 1


@pytest.mark.asyncio
async def test_unregister_only_removes_current(manager):
    old = Connection()
    await manager.register("dev-1", old)

    new = Connection()
    await manager.register("dev-1", new)  # 顶掉 old

    # 尝试用旧连接 unregister，不应误删新连接
    await manager.unregister("dev-1", old)
    assert manager.get("dev-1") is new
    assert manager.active_count == 1

    # 用新连接 unregister，正常移除
    await manager.unregister("dev-1", new)
    assert manager.active_count == 0


@pytest.mark.asyncio
async def test_concurrent_handshake_atomic(manager):
    """并发握手同一 device_id，最终只保留一条连接，且不抛异常。"""
    results = await asyncio.gather(
        *[manager.register("dev-1", Connection()) for _ in range(10)]
    )
    # 只有第一次 register 返回 None（无旧连接），其余都返回被顶掉的旧连接
    replaced_list = [r for r in results if r is not None]
    assert len(replaced_list) == 9
    assert manager.active_count == 1


@pytest.mark.asyncio
async def test_connection_limit():
    manager = ConnectionManager(max_connections=1)
    await manager.register("dev-1", Connection())

    with pytest.raises(ConnectionLimitError):
        await manager.register("dev-2", Connection())


@pytest.mark.asyncio
async def test_replace_handler_called():
    manager = ConnectionManager()
    called = []

    async def on_replace(conn, reason):
        called.append((conn.device_id, reason))

    manager.set_replace_handler(on_replace)

    old = Connection()
    await manager.register("dev-1", old)
    await manager.register("dev-1", Connection())

    await manager.notify_replaced(old, "replaced")
    assert called == [("dev-1", "replaced")]
