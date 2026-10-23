"""管理后台鉴权测试：登录接口 + 管理接口的 token 校验。"""

import pytest
from fastapi.testclient import TestClient

from app.core.admin_auth import AdminAuth
from app.main import create_app


@pytest.fixture
def client():
    app = create_app()
    return TestClient(app)


@pytest.fixture
def admin_headers(client):
    """登录拿 token，返回带 Authorization 头的 headers。"""
    resp = client.post("/api/admin/login", json={"username": "admin", "password": "admin123"})
    assert resp.status_code == 200
    token = resp.json()["token"]
    return {"Authorization": f"Bearer {token}"}


def test_admin_login_success(client):
    resp = client.post("/api/admin/login", json={"username": "admin", "password": "admin123"})
    assert resp.status_code == 200
    data = resp.json()
    assert data["token"]
    assert data["token_type"] == "bearer"


def test_admin_login_wrong_password(client):
    resp = client.post("/api/admin/login", json={"username": "admin", "password": "wrong"})
    assert resp.status_code == 401


def test_admin_login_wrong_username(client):
    resp = client.post("/api/admin/login", json={"username": "nobody", "password": "admin123"})
    assert resp.status_code == 401


def test_admin_auth_core_verify():
    auth = AdminAuth(username="admin", password="pw", token_ttl=0)
    assert auth.login("admin", "pw") is not None
    assert auth.login("admin", "bad") is None
    token = auth.login("admin", "pw")
    assert auth.verify(token) is True
    assert auth.verify("bogus-token") is False
    assert auth.verify(None) is False


def test_admin_auth_core_ttl():
    import time
    auth = AdminAuth(username="admin", password="pw", token_ttl=1)
    token = auth.login("admin", "pw")
    assert auth.verify(token) is True
    time.sleep(1.1)
    assert auth.verify(token) is False


def test_management_endpoint_requires_auth(client):
    """未带 token 访问管理接口应 401。"""
    assert client.get("/api/devices").status_code == 401
    assert client.get("/api/commands").status_code == 401
    assert client.get("/api/connections").status_code == 401
    assert client.get("/api/logs").status_code == 401


def test_management_endpoint_with_auth(client, admin_headers):
    """带 token 访问管理接口应 200。"""
    assert client.get("/api/devices", headers=admin_headers).status_code == 200
    assert client.get("/api/commands", headers=admin_headers).status_code == 200
    assert client.get("/api/connections", headers=admin_headers).status_code == 200


def test_device_register_no_auth_required(client):
    """设备注册接口不应要求管理鉴权（客户端调用）。"""
    resp = client.post("/api/devices/register", json={"device_id": "dev-x", "device_name": "测试"})
    assert resp.status_code == 201


def test_log_upload_no_auth_required(client):
    """日志上传接口不应要求管理鉴权（客户端调用）。"""
    resp = client.post("/api/logs", json={"device_id": "dev-x", "content": "hello"})
    assert resp.status_code == 200
