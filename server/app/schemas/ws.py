"""WebSocket 消息信封的 Pydantic 校验模型。

统一 JSON 信封结构：{"type": ..., "ts": ..., "data": {...}}
详见 docs/server/design.md 第九节「消息协议」。
"""

from __future__ import annotations

import time
from typing import Any, Literal, Optional

from pydantic import BaseModel, Field

# 已知的消息类型
MessageType = Literal["ping", "pong", "close", "message", "hello", "command", "command_result"]


class Envelope(BaseModel):
    """通用消息信封，用于解析入站消息与构造出站消息。"""

    type: MessageType
    ts: Optional[int] = Field(default=None, description="发送方时间戳（毫秒）")
    data: Optional[dict[str, Any]] = Field(default=None)

    @classmethod
    def make(cls, type_: MessageType, data: Optional[dict[str, Any]] = None) -> "Envelope":
        """构造一条带当前时间戳的出站消息。"""
        return cls(type=type_, ts=int(time.time() * 1000), data=data)

    def to_json(self) -> str:
        """序列化为 JSON 字符串（用于通过 WebSocket 发送）。"""
        return self.model_dump_json(exclude_none=True)


class HelloData(BaseModel):
    """握手消息（hello）的 data 载荷：客户端声明自己的设备标识与鉴权 token。"""

    device_id: str = Field(min_length=1, max_length=128)
    token: Optional[str] = Field(default=None, description="鉴权 token（启用鉴权时必填）")
    device_name: Optional[str] = Field(default=None, max_length=128, description="设备昵称（可选，缺省用 device_id）")


class CloseData(BaseModel):
    """close 通知的 data 载荷。"""

    reason: str = Field(default="replaced", description="replaced=被新连接顶掉 / server_shutdown / 其他")
    message: Optional[str] = None


# ============ REST 接口模型 ============

class DeviceRegisterRequest(BaseModel):
    """设备注册请求体。"""

    device_id: str = Field(min_length=1, max_length=128)
    device_name: Optional[str] = Field(default=None, max_length=128, description="设备昵称（可选，缺省用 device_id）")


class DeviceRegisterResponse(BaseModel):
    """设备注册响应。"""

    device_id: str
    token: str


class DeviceListItem(BaseModel):
    """设备列表项（供管理侧查看/挑设备下发命令）。"""

    device_id: str
    device_name: str
    online: bool
    created_at: float


class PushRequest(BaseModel):
    """消息推送请求体。"""

    device_ids: list[str] = Field(min_length=1, description="目标设备列表（至少一个）")
    payload: dict = Field(default_factory=dict, description="推送给客户端的业务数据")


class PushResponse(BaseModel):
    """消息推送响应。"""

    total: int
    delivered: int
    failed: list[str] = Field(default_factory=list, description="推送失败的 device_id 列表")


class CommandData(BaseModel):
    """命令下发（command 消息）的 data 载荷。"""

    command: str = Field(description="命令名，如 upload_log")
    command_id: str = Field(description="命令唯一 ID（用于回执关联）")
    params: dict = Field(default_factory=dict, description="命令参数")


class CommandResultData(BaseModel):
    """命令执行回执（command_result 消息）的 data 载荷。"""

    command_id: str = Field(description="对应命令的 command_id")
    command: str = Field(description="命令名")
    success: bool = Field(description="是否执行成功")
    message: str = Field(default="", description="结果说明（失败原因 / 成功摘要）")


class CommandRequest(BaseModel):
    """命令下发 REST 请求体。"""

    device_id: str = Field(description="目标设备（精确到一个设备）")
    command: str = Field(description="命令名，如 upload_log")
    params: dict = Field(default_factory=dict, description="命令参数")


class CommandResponse(BaseModel):
    """命令下发 REST 响应体。"""

    command_id: str
    device_id: str
    command: str
    delivered: bool = Field(description="是否已送达设备（设备在线则 true）")
