"""命令回执存储层。

服务端向设备下发命令后，客户端异步回执 ``command_result``（成功/失败/说明）。
本模块把这些回执持久化（内存实现，接口可替换），供管理侧查询「某条命令
执行结果」，避免回执仅记日志而丢失。

存储模型：``command_id -> CommandRecord``（下发即登记，回执到达后更新状态）。
限制最大条数（防无限堆积），超限丢弃最旧记录。
"""

from __future__ import annotations

import logging
import time
from collections import OrderedDict
from dataclasses import dataclass, field
from typing import Optional

logger = logging.getLogger(__name__)


@dataclass
class CommandRecord:
    """一条命令的完整生命周期记录。"""

    command_id: str
    device_id: str
    command: str
    params: dict = field(default_factory=dict)
    status: str = "pending"  # pending（已下发未回执）/ success / failed
    result_message: str = ""
    created_at: float = field(default_factory=time.time)
    result_at: Optional[float] = None

    def to_dict(self) -> dict:
        return {
            "command_id": self.command_id,
            "device_id": self.device_id,
            "command": self.command,
            "params": self.params,
            "status": self.status,
            "result_message": self.result_message,
            "created_at": self.created_at,
            "result_at": self.result_at,
        }


class CommandStore:
    """命令回执存储（内存实现，接口可替换）。

    :param max_records: 最大保留条数（0 = 不限），超限丢弃最旧
    """

    def __init__(self, max_records: int = 200) -> None:
        self._records: OrderedDict[str, CommandRecord] = OrderedDict()
        self._max_records = max_records

    def create(self, command_id: str, device_id: str, command: str, params: dict) -> CommandRecord:
        """下发命令时登记一条 pending 记录。"""
        record = CommandRecord(
            command_id=command_id,
            device_id=device_id,
            command=command,
            params=params,
        )
        self._records[command_id] = record
        self._trim()
        logger.info("登记命令 %s（device=%s）", command, device_id)
        return record

    def set_result(self, command_id: str, success: bool, message: str) -> Optional[CommandRecord]:
        """回执到达时更新记录状态。"""
        record = self._records.get(command_id)
        if record is None:
            logger.warning("回执对应命令不存在：%s", command_id)
            return None
        record.status = "success" if success else "failed"
        record.result_message = message
        record.result_at = time.time()
        logger.info("命令 %s 回执：%s", command_id, record.status)
        return record

    def get(self, command_id: str) -> Optional[CommandRecord]:
        return self._records.get(command_id)

    def list(self) -> list[CommandRecord]:
        """全部命令记录（由旧到新）。"""
        return list(self._records.values())

    def count(self) -> int:
        return len(self._records)

    def _trim(self) -> None:
        """超限丢弃最旧记录。"""
        if self._max_records > 0:
            while len(self._records) > self._max_records:
                self._records.popitem(last=False)
