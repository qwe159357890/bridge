"""命令下发接口测试（白名单校验 + 离线/在线分支）。"""

import pytest
from fastapi.testclient import TestClient

from app.core.connection_manager import ConnectionManager
from app.core.device_registry import DeviceRegistry
from app.main import create_app


@pytest.fixture
def client():
    app = create_app()
    return TestClient(app)


@pytest.fixture
def admin_headers(client):
    """登录拿 token，返回带 Authorization 头的 headers。"""
    resp = client.post("/api/admin/login", json={"username": "admin", "password": "admin123"})
    return {"Authorization": f"Bearer {resp.json()['token']}"}


def test_command_not_in_whitelist(client, admin_headers):
    resp = client.post(
        "/api/commands",
        json={"device_id": "dev-1", "command": "evil_cmd", "params": {}},
        headers=admin_headers,
    )
    assert resp.status_code == 400
    assert "不支持的命令" in resp.json()["detail"]


def test_command_device_offline(client, admin_headers):
    resp = client.post(
        "/api/commands",
        json={"device_id": "ghost", "command": "upload_log", "params": {}},
        headers=admin_headers,
    )
    assert resp.status_code == 404
    assert "不在线" in resp.json()["detail"]


def test_device_list_includes_name_and_online(client, admin_headers):
    # 注册两个设备（一个在线一个离线）
    manager: ConnectionManager = client.app.state.connection_manager
    registry: DeviceRegistry = client.app.state.device_registry

    registry.register("dev-1", device_name="客厅手机")
    registry.register("dev-2", device_name="卧室平板")

    from app.models.connection import Connection

    async def _register():
        await manager.register("dev-1", Connection())

    import asyncio
    asyncio.run(_register())

    resp = client.get("/api/devices", headers=admin_headers)
    assert resp.status_code == 200
    devices = resp.json()
    assert len(devices) == 2
    by_id = {d["device_id"]: d for d in devices}
    assert by_id["dev-1"]["device_name"] == "客厅手机"
    assert by_id["dev-1"]["online"] is True
    assert by_id["dev-2"]["device_name"] == "卧室平板"
    assert by_id["dev-2"]["online"] is False


def test_command_store_create_and_result():
    """命令登记 + 回执更新（持久化核心逻辑）。"""
    from app.core.command_store import CommandStore

    store = CommandStore(max_records=3)
    store.create("cmd-1", "dev-1", "upload_log", {})
    assert store.get("cmd-1").status == "pending"

    store.set_result("cmd-1", success=True, message="上传成功")
    r = store.get("cmd-1")
    assert r.status == "success"
    assert r.result_message == "上传成功"
    assert r.result_at is not None


def test_command_store_max_records():
    """超限丢弃最旧记录（限制最大数量）。"""
    from app.core.command_store import CommandStore

    store = CommandStore(max_records=2)
    store.create("cmd-1", "dev-1", "upload_log", {})
    store.create("cmd-2", "dev-1", "upload_log", {})
    store.create("cmd-3", "dev-1", "upload_log", {})
    assert store.count() == 2
    assert store.get("cmd-1") is None  # 最旧被丢弃
    assert store.get("cmd-2") is not None
    assert store.get("cmd-3") is not None


def test_command_query_endpoints(client, admin_headers):
    """查询接口：列表 + 单条查询 + 404。"""
    from app.core.command_store import CommandStore

    client.app.state.command_store = CommandStore()
    client.app.state.command_store.create("cmd-x", "dev-1", "upload_log", {})
    client.app.state.command_store.set_result("cmd-x", True, "ok")

    resp = client.get("/api/commands", headers=admin_headers)
    assert resp.status_code == 200
    records = resp.json()
    assert len(records) == 1
    assert records[0]["command_id"] == "cmd-x"

    resp = client.get("/api/commands/cmd-x", headers=admin_headers)
    assert resp.status_code == 200
    assert resp.json()["status"] == "success"
    assert resp.json()["result_message"] == "ok"

    resp = client.get("/api/commands/not-exist", headers=admin_headers)
    assert resp.status_code == 404
