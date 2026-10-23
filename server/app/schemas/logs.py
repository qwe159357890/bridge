"""日志上传/读取接口的 Pydantic 校验模型。"""

from __future__ import annotations

from typing import Optional

from pydantic import BaseModel, Field


class LogUploadRequest(BaseModel):
    """客户端上传日志的请求体（借鉴 aurelay DiagUploader 的上报字段）。"""

    device_id: str = Field(min_length=1, max_length=128)
    content: str = Field(min_length=1, description="日志文本内容")
    device_name: Optional[str] = Field(default="", max_length=128, description="设备型号/名称")
    app_version: Optional[str] = Field(default="", max_length=64, description="客户端版本")


class LogUploadResponse(BaseModel):
    """日志上传响应。"""

    code: int = Field(default=0, description="0=成功，非 0=业务错误")
    message: str = Field(default="上传成功")
    device_id: str


class LogEntryResponse(BaseModel):
    """单条日志的响应模型。"""

    device_id: str
    device_name: str
    app_version: str
    content: str
    created_at: float


class LogListResponse(BaseModel):
    """某设备日志列表响应。"""

    device_id: str
    total: int
    logs: list[LogEntryResponse] = Field(default_factory=list)
