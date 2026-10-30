"""日志存储与上传接口单元测试（2026-10-29 按行存储重构后）。"""

import pytest

from app.core.log_store import LogStore


@pytest.fixture
def store():
    return LogStore(max_entries_per_device=5, max_content_chars=1000)


def _line(ts, level="I", tag="Bridge", message="msg"):
    return {"ts": float(ts), "level": level, "tag": tag, "message": message}


def test_add_and_list(store):
    store.add("dev-1", logs=[_line(1000.0, message="第一行"), _line(1001.0, message="第二行")])
    entries = store.list("dev-1")
    assert len(entries) == 2
    # 由新到旧：最新在前
    assert entries[0].message == "第二行"
    assert entries[1].message == "第一行"


def test_add_returns_inserted_count(store):
    n = store.add("dev-1", logs=[_line(1000.0), _line(1001.0)])
    assert n == 2
    # 重复上传同一批 → 去重后新增 0
    n2 = store.add("dev-1", logs=[_line(1000.0), _line(1001.0)])
    assert n2 == 0


def test_dedupe_incremental(store):
    store.add("dev-1", logs=[_line(1000.0), _line(1001.0)])
    # 一条旧 + 一条新 → 只新增 1 条
    n = store.add("dev-1", logs=[_line(1000.0), _line(2000.0, message="新")])
    assert n == 1
    assert store.count("dev-1") == 3


def test_get_latest(store):
    store.add("dev-1", logs=[_line(1000.0, message="旧"), _line(1001.0, message="新")])
    assert store.get_latest("dev-1").message == "新"


def test_list_unknown_device_empty(store):
    assert store.list("unknown") == []
    assert store.get_latest("unknown") is None


def test_content_truncated(store):
    store.add("dev-1", logs=[_line(1000.0, message="x" * 1500)])
    assert len(store.get_latest("dev-1").message) == 1000


def test_max_entries_per_device(store):
    for i in range(10):
        store.add("dev-1", logs=[_line(float(i), message=f"log-{i}")])
    entries = store.list("dev-1")
    assert len(entries) == 5
    # 保留最新，丢弃最旧；由新到旧排序
    assert entries[0].message == "log-9"
    assert entries[-1].message == "log-5"


def test_clear(store):
    store.add("dev-1", logs=[_line(1.0)])
    store.add("dev-1", logs=[_line(2.0)])
    store.add("dev-2", logs=[_line(3.0)])
    assert store.clear("dev-1") == 2
    assert store.list("dev-1") == []
    assert store.list("dev-2") != []


def test_devices_and_count(store):
    store.add("dev-1", logs=[_line(1.0)])
    store.add("dev-2", logs=[_line(2.0)])
    store.add("dev-2", logs=[_line(3.0)])
    assert store.devices() == ["dev-1", "dev-2"]
    assert store.count() == 3
    assert store.count("dev-2") == 2


def test_filter_level_tag_keyword(store):
    store.add(
        "dev-1",
        logs=[
            _line(1000.0, level="I", tag="Bridge", message="启动完成"),
            _line(1001.0, level="W", tag="中继", message="连接失败"),
            _line(1002.0, level="E", tag="解码", message="越界"),
        ],
    )
    assert len(store.list("dev-1", level="E")) == 1
    assert store.list("dev-1", level="E")[0].tag == "解码"
    assert all(e.tag == "Bridge" for e in store.list("dev-1", tag="Bridge"))
    assert len(store.list("dev-1", keyword="越界")) == 1


def test_pagination():
    # 分页测试需要不限条数（否则 max_entries=5 会裁剪）
    store = LogStore(max_entries_per_device=0, max_content_chars=1000)
    for i in range(25):
        store.add("dev-1", logs=[_line(float(i), message=f"m{i}")])
    page1 = store.list("dev-1", offset=0, limit=10)
    page2 = store.list("dev-1", offset=10, limit=10)
    page3 = store.list("dev-1", offset=20, limit=10)
    # 由新到旧：第 1 页顶部是最新 m24
    assert len(page1) == 10 and page1[0].message == "m24" and page1[-1].message == "m15"
    assert len(page2) == 10 and page2[0].message == "m14"
    assert len(page3) == 5 and page3[-1].message == "m0"
    # limit=0 返回全部（向后兼容）
    assert len(store.list("dev-1")) == 25


def test_content_legacy_path(store):
    """旧客户端整块文本 content 仍能按行拆入（message 字段）。"""
    store.add("dev-1", "10-29 09:36:29.590 [I] [Bridge] 位置兜底上报成功\n10-29 09:36:30.000 [W] [中继] 连接失败")
    entries = store.list("dev-1")
    assert len(entries) == 2
    # 由新到旧：第二行（连接失败）在前
    assert entries[0].level == "W" and entries[0].tag == "中继"
    assert entries[1].level == "I" and entries[1].tag == "Bridge"


def test_content_stack_fragment_merged(store):
    """整块文本里的异常堆栈碎片（\\tat / Caused by）应归并到上一条，而非独立成 tag 空脏行。"""
    content = (
        "10-29 09:36:29.590 [W] [Bridge] 位置兜底上报失败\n"
        "retrofit2.HttpException: HTTP 401 Unauthorized\n"
        "\tat retrofit2.KotlinExtensions$await$2$2.onResponse(KotlinExtensions.kt:53)\n"
        "Caused by: java.net.ConnectException: failed to connect\n"
        "\t... 18 more"
    )
    store.add("dev-1", content)
    entries = store.list("dev-1")
    # 只有 1 条：堆栈碎片被归并进上一条 [W] [Bridge]
    assert len(entries) == 1
    e = entries[0]
    assert e.level == "W" and e.tag == "Bridge"
    assert "HttpException" in e.message
    assert "\tat retrofit2" in e.message
    # 无 tag 空的独立脏行
    assert all(x.tag != "" for x in store.list("dev-1"))


def test_content_any_multiline_merged(store):
    """防御性：任何解析失败的多行续行（非堆栈，含未来未知形态）都应归并，而非丢成 tag 空脏行。"""
    content = (
        "10-29 09:36:29.590 [I] [Bridge] 一段正常的首行\n"
        "这是没有级别标记的第二行续行\n"
        "这是第三行，同样无标记\n"
        "10-29 09:36:30.000 [W] [中继] 下一条独立日志"
    )
    store.add("dev-1", content)
    entries = store.list("dev-1")
    # 2 条：两条 [I]/[W] 独立日志 + 中间两行无标记续行归并进第一条
    assert len(entries) == 2
    newest = entries[0]  # 由新到旧，第一项是 [W]
    assert newest.level == "W" and newest.tag == "中继"
    older = entries[1]   # [I]，message 含归并的续行
    assert older.level == "I" and older.tag == "Bridge"
    assert "第二行续行" in older.message
    assert "第三行" in older.message
    # message 里绝无真实换行，只有 ⏎ 占位符（与客户端 flattenNewlines 语义一致）
    assert "\n" not in older.message
    assert "⏎" in older.message
    # 无 tag 空独立脏行
    assert all(x.tag != "" for x in entries)

