"""日志上传/读取接口的 Pydantic 校验模型。

2026-10-29 重构：日志从「整块文本」改为「按行存储」——
客户端上传结构化日志行数组（每行含 ts/level/tag/message），
服务端按行持久化，后台按行分页/过滤/检索。
"""

from __future__ import annotations

from typing import Optional

from pydantic import BaseModel, Field


class LogLine(BaseModel):
    """一行结构化日志（客户端解析后上传）。"""

    ts: float = Field(description="日志产生时间（epoch 毫秒）")
    level: str = Field(default="I", max_length=8, description="级别：I/W/E/D/V")
    tag: str = Field(default="", max_length=64, description="模块标签，如 中继/音轨/解码")
    message: str = Field(max_length=4096, description="日志正文")


class LogUploadRequest(BaseModel):
    """客户端上传日志的请求体。

    ``logs`` 为结构化日志行数组；``content`` 保留整块文本（兜底/旧客户端兼容），
    两者可同时存在：新客户端传 logs，旧客户端传 content，服务端统一按行入库。
    """

    device_id: str = Field(min_length=1, max_length=128)
    logs: list[LogLine] = Field(default_factory=list, description="结构化日志行数组")
    content: Optional[str] = Field(default="", description="整块日志文本（旧客户端兼容，可为空）")
    device_name: Optional[str] = Field(default="", max_length=128, description="设备型号/名称")
    app_version: Optional[str] = Field(default="", max_length=64, description="客户端版本")


class LogUploadResponse(BaseModel):
    """日志上传响应。"""

    code: int = Field(default=0, description="0=成功，非 0=业务错误")
    message: str = Field(default="上传成功")
    device_id: str
    stored: int = Field(default=0, description="实际入库的日志行数")


class LogEntryResponse(BaseModel):
    """单条日志（一行）的响应模型。"""

    device_id: str
    device_name: str
    app_version: str
    ts: float
    level: str
    tag: str
    message: str
    created_at: float = Field(description="入库时间（epoch 秒），兼容旧字段")


class LogListResponse(BaseModel):
    """某设备日志列表响应（按行分页）。"""

    device_id: str
    total: int = Field(default=0, description="该设备日志总行数")
    offset: int = Field(default=0, description="当前页起始（0 起）")
    limit: int = Field(default=0, description="每页条数（0 = 不分页，返回全部）")
    logs: list[LogEntryResponse] = Field(default_factory=list)
