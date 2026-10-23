"""设备注册与鉴权存储。

当前用内存字典实现（单进程内有效），接口与存储解耦，
后续可替换为数据库/Redis 而无需改动调用方。

鉴权模型：
- 客户端首次启动时向服务端注册，获得 ``device_id`` + ``token``。
- 之后连接时携带 ``token``，服务端校验通过才允许建立连接。
- token 用 ``secrets.token_hex`` 生成，足够随机不可猜测。
"""

from __future__ import annotations

import logging
import secrets
import time
from dataclasses import dataclass
from typing import Optional

logger = logging.getLogger(__name__)


@dataclass
class Device:
    """已注册设备。"""

    device_id: str
    token: str
    created_at: float
    last_seen_at: float
    metadata: dict = None  # 预留：设备型号、系统版本等
    device_name: str = ""  # 设备昵称（缺省用 device_id）

    def __post_init__(self) -> None:
        if self.metadata is None:
            self.metadata = {}
        if not self.device_name:
            self.device_name = self.device_id


class DeviceRegistry:
    """设备注册表（内存实现，接口可替换）。"""

    def __init__(self, token_ttl: int = 0) -> None:
        self._devices: dict[str, Device] = {}
        self._token_ttl = token_ttl

    def register(
        self,
        device_id: str,
        metadata: Optional[dict] = None,
        device_name: Optional[str] = None,
    ) -> Device:
        """注册（或重新注册）一个设备，返回带 token 的 Device。

        重复注册同一 device_id 会刷新 token（旧 token 失效）并更新昵称。
        """
        token = secrets.token_hex(32)
        now = time.time()
        device = Device(
            device_id=device_id,
            token=token,
            created_at=now,
            last_seen_at=now,
            metadata=metadata or {},
            device_name=device_name or device_id,
        )
        self._devices[device_id] = device
        logger.info("设备注册成功：%s（昵称=%s）", device_id, device.device_name)
        return device

    def update_name(self, device_id: str, device_name: str) -> Optional[Device]:
        """更新设备昵称（不刷新 token）。设备不存在返回 None。"""
        device = self._devices.get(device_id)
        if device is None:
            return None
        if device_name:
            device.device_name = device_name
        return device

    def authenticate(self, device_id: str, token: str) -> bool:
        """校验 device_id + token 是否匹配且未过期。"""
        device = self._devices.get(device_id)
        if device is None:
            return False
        # token 常量时间比较，避免时序攻击
        if not secrets.compare_digest(device.token, token):
            return False
        if self._token_ttl > 0:
            if time.time() - device.last_seen_at > self._token_ttl:
                return False
        device.last_seen_at = time.time()
        return True

    def get(self, device_id: str) -> Optional[Device]:
        return self._devices.get(device_id)

    def list_devices(self) -> list[Device]:
        """列出所有已注册设备（按 device_id 排序）。"""
        return [self._devices[k] for k in sorted(self._devices.keys())]

    def remove(self, device_id: str) -> None:
        self._devices.pop(device_id, None)

    def count(self) -> int:
        return len(self._devices)
