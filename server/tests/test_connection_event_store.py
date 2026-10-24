"""连接事件存储（上下线记录）单元测试。"""

from app.core.connection_event_store import ConnectionEventStore


def test_record_and_list_order():
    store = ConnectionEventStore(max_events=10)
    store.record("d1", "online", "")
    store.record("d1", "offline", "read_timeout")
    store.record("d2", "online", "")

    events = store.list()
    # 时间倒序：最新在前
    assert events[0]["device_id"] == "d2"
    assert events[0]["event"] == "online"
    assert events[1]["device_id"] == "d1"
    assert events[1]["event"] == "offline"
    assert events[1]["reason"] == "read_timeout"


def test_filter_by_device():
    store = ConnectionEventStore(max_events=10)
    store.record("d1", "online")
    store.record("d2", "online")
    store.record("d1", "offline", "closed")

    d1 = store.list(device_id="d1")
    assert all(e["device_id"] == "d1" for e in d1)
    assert len(d1) == 2


def test_limit():
    store = ConnectionEventStore(max_events=10)
    store.record("d1", "online")
    store.record("d1", "offline", "closed")

    events = store.list(limit=1)
    assert len(events) == 1


def test_max_events_evicts_oldest():
    store = ConnectionEventStore(max_events=3)
    for i in range(5):
        store.record("d1", "online")

    assert store.count() == 3


def test_count():
    store = ConnectionEventStore(max_events=100)
    assert store.count() == 0
    store.record("d1", "online")
    store.record("d1", "offline", "closed")
    assert store.count() == 2
