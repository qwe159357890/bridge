"""消息信封单元测试。"""

from app.schemas.ws import Envelope


def test_envelope_make_and_serialize():
    env = Envelope.make("pong")
    data = env.model_dump()
    assert data["type"] == "pong"
    assert data["ts"] is not None
    assert data.get("data") is None  # exclude_none 后 data 不出现

    raw = env.to_json()
    assert '"type":"pong"' in raw


def test_envelope_parse_hello():
    env = Envelope.model_validate_json('{"type":"hello","ts":1,"data":{"device_id":"dev-1"}}')
    assert env.type == "hello"
    assert env.data["device_id"] == "dev-1"
