"""设备注册与鉴权单元测试。"""

import pytest

from app.core.device_registry import DeviceRegistry


@pytest.fixture
def registry():
    return DeviceRegistry()


def test_register_returns_token(registry):
    device = registry.register("dev-1")
    assert device.device_id == "dev-1"
    assert device.token
    assert len(device.token) == 64  # secrets.token_hex(32)


def test_authenticate_success(registry):
    device = registry.register("dev-1")
    assert registry.authenticate("dev-1", device.token) is True


def test_authenticate_wrong_token(registry):
    registry.register("dev-1")
    assert registry.authenticate("dev-1", "wrong-token") is False


def test_authenticate_unknown_device(registry):
    assert registry.authenticate("unknown", "any-token") is False


def test_reregister_refreshes_token(registry):
    d1 = registry.register("dev-1")
    d2 = registry.register("dev-1")
    assert d1.token != d2.token
    # 旧 token 失效
    assert registry.authenticate("dev-1", d1.token) is False
    assert registry.authenticate("dev-1", d2.token) is True


def test_count(registry):
    registry.register("dev-1")
    registry.register("dev-2")
    assert registry.count() == 2
    registry.remove("dev-1")
    assert registry.count() == 1


def test_register_with_name(registry):
    device = registry.register("dev-1", device_name="客厅手机")
    assert device.device_name == "客厅手机"


def test_register_default_name_is_device_id(registry):
    device = registry.register("dev-1")
    assert device.device_name == "dev-1"


def test_update_name(registry):
    registry.register("dev-1")
    updated = registry.update_name("dev-1", "新昵称")
    assert updated is not None
    assert updated.device_name == "新昵称"


def test_update_name_unknown_device(registry):
    assert registry.update_name("unknown", "x") is None


def test_list_devices_sorted(registry):
    registry.register("dev-b", device_name="B")
    registry.register("dev-a", device_name="A")
    ids = [d.device_id for d in registry.list_devices()]
    assert ids == ["dev-a", "dev-b"]
