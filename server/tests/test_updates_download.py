"""扫码下载接口测试（免鉴权下载最新 APK + 二维码落地页）。"""

from pathlib import Path
from types import SimpleNamespace

import pytest
from fastapi.testclient import TestClient

from app.main import create_app


@pytest.fixture
def updates_dir(tmp_path: Path) -> Path:
    """临时更新目录，预置两个版本的 APK + 一个 update.json。"""
    d = tmp_path / "updates"
    d.mkdir()
    (d / "bridge-v0.2.43.apk").write_bytes(b"fake-apk-043")
    (d / "bridge-v0.2.44.apk").write_bytes(b"fake-apk-044")
    (d / "update.json").write_text(
        '{"version": "0.2.44", "notes": "修复两项：微信离开即结束 / 退出不复活"}',
        encoding="utf-8",
    )
    return d


@pytest.fixture
def client(monkeypatch, updates_dir: Path) -> TestClient:
    """把更新目录指到临时目录，隔离仓库目录；auth 关闭以便聚焦免鉴权行为。"""
    monkeypatch.setattr(
        "app.api.v1.updates.get_settings",
        lambda: SimpleNamespace(
            updates=SimpleNamespace(dir=str(updates_dir), public_base_url="http://192.168.1.10:15160"),
        ),
    )
    app = create_app()
    return TestClient(app)


def test_download_page_renders_qrcode_and_version(client):
    resp = client.get("/download")
    assert resp.status_code == 200
    text = resp.text
    assert "Bridge 客户端" in text
    assert "qrcode" in text
    assert "0.2.44" in text  # 版本号
    assert "bridge-v0.2.44.apk" in text  # 文件名
    assert "/d" in text  # 短链下载端点（越短越好）
    # public_base_url 应体现在二维码/下载链接里
    assert "192.168.1.10" in text
    assert "192.168.1.10:15160/d" in text  # 下载链接 = base + /d 短链


def test_download_info_returns_public_urls(client):
    """/api/download/info 应返回用 public_base_url 推导的短链 apk_url 与落地页 page_url。"""
    resp = client.get("/api/download/info")
    assert resp.status_code == 200
    data = resp.json()
    assert data["apk_url"] == "http://192.168.1.10:15160/d"
    assert data["page_url"] == "http://192.168.1.10:15160/download"


def test_download_short_link_d(client):
    """短链 /d 与 /api/download/app 别名等价，都能免鉴权下到最高版本 APK。"""
    r_short = client.get("/d")
    r_alias = client.get("/api/download/app")
    assert r_short.status_code == 200
    assert r_alias.status_code == 200
    assert r_short.content == b"fake-apk-044"
    assert r_alias.content == b"fake-apk-044"
    assert r_short.headers["content-type"] == "application/vnd.android.package-archive"


def test_download_latest_apk_no_auth(client):
    """免鉴权下载：不带任何设备头也能拿到版本最高的 APK。"""
    resp = client.get("/api/download/app")
    assert resp.status_code == 200
    assert resp.content == b"fake-apk-044"  # 版本最高的是 0.2.44
    assert resp.headers["content-type"] == "application/vnd.android.package-archive"
    # 文件名应随 attachment 下发，触发系统识别为可安装 APK
    assert "bridge-v0.2.44.apk" in resp.headers["content-disposition"]


def test_download_latest_apk_picks_highest_version(client):
    """扫描应选版本最高的 APK（0.2.44 > 0.2.43）。"""
    resp = client.get("/api/download/app")
    assert resp.content == b"fake-apk-044"


def test_download_latest_apk_404_when_empty(tmp_path, monkeypatch):
    """更新目录无 APK 时应 404，落地页应返回「暂无」页面。"""
    empty = tmp_path / "empty"
    empty.mkdir()
    monkeypatch.setattr(
        "app.api.v1.updates.get_settings",
        lambda: SimpleNamespace(
            updates=SimpleNamespace(dir=str(empty), public_base_url=""),
        ),
    )
    app = create_app()
    client = TestClient(app)
    assert client.get("/api/download/app").status_code == 404
    page = client.get("/download")
    assert page.status_code == 200
    assert "暂无" in page.text
