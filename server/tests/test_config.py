"""配置加载单元测试：YAML 加载、环境变量覆盖、参数校验 fail-fast。"""

import os
from pathlib import Path

import pytest
import yaml

from app import config as config_module
from app.config import Settings, get_settings


@pytest.fixture
def clean_env(monkeypatch):
    """清除 BRIDGE_* 环境变量与缓存，保证测试隔离。"""
    for key in list(os.environ):
        if key.startswith("BRIDGE_"):
            monkeypatch.delenv(key)
    get_settings.cache_clear()
    yield
    get_settings.cache_clear()


def test_default_settings(clean_env):
    s = get_settings()
    assert s.heartbeat.interval == 20
    assert s.heartbeat.server_read_timeout == 45
    assert s.reconnect.max_delay == 60
    assert s.connection.unique_per_device is True


def test_env_override(clean_env, monkeypatch):
    monkeypatch.setenv("BRIDGE_HEARTBEAT_INTERVAL", "30")
    s = get_settings()
    assert s.heartbeat.interval == 30


def test_config_file_override(clean_env, monkeypatch, tmp_path):
    """BRIDGE_CONFIG 指定的额外 YAML 覆盖默认值。"""
    extra = tmp_path / "config.prod.yaml"
    extra.write_text(
        yaml.safe_dump({"heartbeat": {"interval": 55, "server_read_timeout": 120}}),
        encoding="utf-8",
    )
    monkeypatch.setenv("BRIDGE_CONFIG", str(extra))
    s = get_settings()
    assert s.heartbeat.interval == 55
    assert s.heartbeat.server_read_timeout == 120


def test_invalid_read_timeout_fails(clean_env):
    """server_read_timeout <= interval 应校验失败。"""
    with pytest.raises(ValueError):
        Settings(heartbeat={"interval": 20, "server_read_timeout": 10})


def test_invalid_reconnect_fails(clean_env):
    """max_delay < base_delay 应校验失败。"""
    with pytest.raises(ValueError):
        Settings(reconnect={"base_delay": 60, "max_delay": 1})
