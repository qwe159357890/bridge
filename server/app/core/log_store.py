"""日志存储层。

借鉴 aurelay 的「诊断日志上传/读取」语义，服务端接收客户端上传的日志文本，
供排查问题。当前用内存实现（单进程内有效），接口与存储解耦，
后续可替换为文件落盘 / 数据库 / 对象存储而无需改动调用方。

存储模型：``device_id -> 日志条目列表``。每次上传追加一条 LogEntry，
保留最近 ``max_entries_per_device`` 条（防止单设备无限堆积）。
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Optional

logger = logging.getLogger(__name__)


@dataclass
class LogEntry:
    """一条设备日志。"""

    device_id: str
    content: str
    device_name: str = ""
    app_version: str = ""
    created_at: float = field(default_factory=time.time)

    def to_dict(self) -> dict:
        return {
            "device_id": self.device_id,
            "device_name": self.device_name,
            "app_version": self.app_version,
            "content": self.content,
            "created_at": self.created_at,
        }


class LogStore:
    """日志存储（内存实现，接口可替换）。

    :param max_entries_per_device: 单设备保留的最大日志条数（0 = 不限）
    :param max_content_chars: 单条日志内容最大字符数（超长截断尾部，防请求体过大）
    """

    def __init__(self, max_entries_per_device: int = 50, max_content_chars: int = 200_000) -> None:
        self._logs: dict[str, list[LogEntry]] = {}
        self._max_entries = max_entries_per_device
        self._max_content = max_content_chars

    def add(
        self,
        device_id: str,
        content: str,
        device_name: str = "",
        app_version: str = "",
    ) -> LogEntry:
        """追加一条日志，返回该条目。

        超长内容截断尾部（保留最新），超限条目数丢弃最旧。
        """
        text = content if len(content) <= self._max_content else content[-self._max_content:]
        entry = LogEntry(
            device_id=device_id,
            content=text,
            device_name=device_name,
            app_version=app_version,
        )
        entries = self._logs.setdefault(device_id, [])
        entries.append(entry)
        if self._max_entries > 0 and len(entries) > self._max_entries:
            del entries[: len(entries) - self._max_entries]
        logger.info("设备 %s 上传日志（%d 字）", device_id, len(text))
        return entry

    def list(self, device_id: str) -> list[LogEntry]:
        """某设备的所有日志（由旧到新）。"""
        return list(self._logs.get(device_id, []))

    def get_latest(self, device_id: str) -> Optional[LogEntry]:
        """某设备最新一条日志。"""
        entries = self._logs.get(device_id)
        return entries[-1] if entries else None

    def clear(self, device_id: str) -> int:
        """清除某设备日志，返回清除的条数。"""
        removed = len(self._logs.pop(device_id, []))
        logger.info("清除设备 %s 的日志（%d 条）", device_id, removed)
        return removed

    def devices(self) -> list[str]:
        """列出有日志的设备 id。"""
        return sorted(self._logs.keys())

    def count(self, device_id: Optional[str] = None) -> int:
        """日志条数统计（不传 device_id 时返回全部条目总数）。"""
        if device_id is not None:
            return len(self._logs.get(device_id, []))
        return sum(len(v) for v in self._logs.values())
