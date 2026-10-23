"""日志存储与上传接口单元测试。"""

import pytest

from app.core.log_store import LogStore


@pytest.fixture
def store():
    return LogStore(max_entries_per_device=5, max_content_chars=1000)


def test_add_and_list(store):
    store.add("dev-1", "第一行日志", device_name="Pixel", app_version="0.1.0")
    store.add("dev-1", "第二行日志")
    entries = store.list("dev-1")
    assert len(entries) == 2
    assert entries[0].content == "第一行日志"
    assert entries[0].device_name == "Pixel"
    assert entries[0].app_version == "0.1.0"


def test_get_latest(store):
    store.add("dev-1", "旧日志")
    store.add("dev-1", "新日志")
    assert store.get_latest("dev-1").content == "新日志"


def test_list_unknown_device_empty(store):
    assert store.list("unknown") == []
    assert store.get_latest("unknown") is None


def test_content_truncated(store):
    store.add("dev-1", "x" * 1500)
    assert len(store.get_latest("dev-1").content) == 1000


def test_max_entries_per_device(store):
    for i in range(10):
        store.add("dev-1", f"log-{i}")
    entries = store.list("dev-1")
    assert len(entries) == 5
    # 保留最新，丢弃最旧
    assert entries[0].content == "log-5"
    assert entries[-1].content == "log-9"


def test_clear(store):
    store.add("dev-1", "a")
    store.add("dev-1", "b")
    store.add("dev-2", "c")
    assert store.clear("dev-1") == 2
    assert store.list("dev-1") == []
    assert store.list("dev-2") != []


def test_devices_and_count(store):
    store.add("dev-1", "a")
    store.add("dev-2", "b")
    store.add("dev-2", "c")
    assert store.devices() == ["dev-1", "dev-2"]
    assert store.count() == 3
    assert store.count("dev-2") == 2
