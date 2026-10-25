"""音乐共享下载接口测试（设备鉴权 + 文件名校验 + 命中/404）。"""

from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.main import create_app


@pytest.fixture
def music_dir(tmp_path: Path) -> Path:
    """临时音乐共享目录，预置一个音乐文件和一个非音乐文件。"""
    d = tmp_path / "music"
    d.mkdir()
    (d / "test.mp3").write_bytes(b"fake-mp3-bytes")
    (d / "note.txt").write_bytes(b"not-music")
    return d


@pytest.fixture
def client(monkeypatch, music_dir: Path) -> TestClient:
    """把音乐目录指到临时目录，隔离仓库目录。"""
    monkeypatch.setattr(
        "app.api.v1.music.get_settings",
        lambda: SimpleNamespace(music=SimpleNamespace(dir=str(music_dir))),
    )
    app = create_app()
    return TestClient(app)


def _device_headers(client: TestClient) -> dict:
    """注册设备拿 token，返回设备鉴权头（config.yaml 默认 auth.enabled=true）。"""
    registry = client.app.state.device_registry
    device = registry.register("dev-music")
    return {"X-Device-Id": device.device_id, "X-Device-Token": device.token}


def test_download_requires_device_auth(client):
    resp = client.get("/api/music/test.mp3")
    assert resp.status_code == 401


def test_download_wrong_token_rejected(client):
    headers = _device_headers(client)
    headers["X-Device-Token"] = "bad-token"
    resp = client.get("/api/music/test.mp3", headers=headers)
    assert resp.status_code == 401


def test_download_success(client):
    resp = client.get("/api/music/test.mp3", headers=_device_headers(client))
    assert resp.status_code == 200
    assert resp.content == b"fake-mp3-bytes"
    assert resp.headers["content-type"].startswith("audio/")


def test_download_missing_file_404(client):
    resp = client.get("/api/music/missing.mp3", headers=_device_headers(client))
    assert resp.status_code == 404
    assert "无此文件" in resp.json()["detail"]


def test_download_rejects_non_music_extension(client):
    """共享目录里混入的非音乐文件不允许外发。"""
    resp = client.get("/api/music/note.txt", headers=_device_headers(client))
    assert resp.status_code == 400
    assert "不支持的音乐格式" in resp.json()["detail"]


def test_download_rejects_traversal(client):
    """含 ``..`` 或 ``\\`` 的文件名一律拒绝（防路径穿越）。"""
    resp = client.get("/api/music/a..b.mp3", headers=_device_headers(client))
    assert resp.status_code == 400
    assert "仅支持单级文件名" in resp.json()["detail"]

    resp = client.get("/api/music/a%5Cb.mp3", headers=_device_headers(client))
    assert resp.status_code == 400
