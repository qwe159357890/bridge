"""音乐播放状态上报与查询测试。

ws.py 的处理逻辑提取为 ``_handle_music_state_message`` / ``_clear_music_state``
两个协程（TestClient 的 WS 会话在当前 starlette 版本存在兼容性问题，
无法做端到端 WS 测试），这里用 fake app + fake 事件总线直测逻辑，
REST 查询端点走 HTTP 集成测试。

覆盖：
- MusicStateStore 单元行为（set/clear/get/非法状态）；
- 上报处理：合法状态存储 + 事件广播；非法状态忽略；
- 断连清理：状态清除 + unknown 事件广播；
- 查询端点：返回最新状态、要求管理鉴权。
"""

from __future__ import annotations

import asyncio
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.api.ws import _clear_music_state, _handle_music_state_message
from app.core.music_state_store import MusicStateStore
from app.main import create_app


# ---------------------------------------------------------------------------
# MusicStateStore 单元测试
# ---------------------------------------------------------------------------


def test_store_set_and_get():
    store = MusicStateStore()
    assert store.set("d1", "playing", "test.mp3") is True
    got = store.get("d1")
    assert got["state"] == "playing"
    assert got["source"] == "test.mp3"
    assert got["updated_at"] is not None


def test_store_invalid_state_rejected():
    store = MusicStateStore()
    assert store.set("d1", "bogus") is False
    assert store.get("d1")["state"] == "unknown"


def test_store_clear_returns_unknown():
    store = MusicStateStore()
    store.set("d1", "paused", "a.mp3")
    store.clear("d1")
    assert store.get("d1")["state"] == "unknown"


# ---------------------------------------------------------------------------
# ws.py 处理逻辑（fake app + fake bus 直测）
# ---------------------------------------------------------------------------


class FakeBus:
    """记录 publish 调用的假事件总线。"""

    def __init__(self):
        self.events: list[tuple[str, dict]] = []

    async def publish(self, event_type: str, data: dict) -> None:
        self.events.append((event_type, data))


@pytest.fixture
def fake_app():
    bus = FakeBus()
    app = SimpleNamespace(
        state=SimpleNamespace(music_state_store=MusicStateStore(), admin_event_bus=bus)
    )
    return app


def test_handle_report_stores_and_publishes(fake_app):
    asyncio.run(_handle_music_state_message(
        fake_app, "d1", {"event": "music_state", "state": "playing", "source": "test.mp3"}
    ))
    assert fake_app.state.music_state_store.get("d1")["state"] == "playing"
    assert fake_app.state.admin_event_bus.events == [
        ("music_state", {"device_id": "d1", "state": "playing", "source": "test.mp3"})
    ]


def test_handle_invalid_state_ignored(fake_app):
    asyncio.run(_handle_music_state_message(
        fake_app, "d1", {"event": "music_state", "state": "bogus"}
    ))
    assert fake_app.state.music_state_store.get("d1")["state"] == "unknown"
    assert fake_app.state.admin_event_bus.events == []


def test_clear_publishes_unknown_event(fake_app):
    fake_app.state.music_state_store.set("d1", "paused", "a.mp3")
    asyncio.run(_clear_music_state(fake_app, "d1"))
    assert fake_app.state.music_state_store.get("d1")["state"] == "unknown"
    assert fake_app.state.admin_event_bus.events == [
        ("music_state", {"device_id": "d1", "state": "unknown", "source": None})
    ]


# ---------------------------------------------------------------------------
# REST 查询端点（HTTP 集成测试）
# ---------------------------------------------------------------------------


@pytest.fixture
def client() -> TestClient:
    return TestClient(create_app())


def _admin_headers(client: TestClient) -> dict:
    r = client.post("/api/admin/login", json={"username": "admin", "password": "admin123"})
    return {"Authorization": f"Bearer {r.json()['token']}"}


def test_query_returns_stored_state(client):
    store = client.app.state.music_state_store
    store.set("dev-x", "playing", "test.mp3")
    headers = _admin_headers(client)
    resp = client.get("/api/devices/dev-x/music-state", headers=headers)
    assert resp.status_code == 200
    body = resp.json()
    assert body == {
        "device_id": "dev-x",
        "state": "playing",
        "source": "test.mp3",
        "updated_at": body["updated_at"],
    }


def test_query_unknown_when_never_reported(client):
    headers = _admin_headers(client)
    body = client.get("/api/devices/dev-none/music-state", headers=headers).json()
    assert body["state"] == "unknown"
    assert body["updated_at"] is None


def test_query_requires_admin_auth(client):
    resp = client.get("/api/devices/dev-x/music-state")
    assert resp.status_code in (401, 403)
