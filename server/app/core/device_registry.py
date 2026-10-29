"""设备注册与鉴权存储。

鉴权模型：
- 客户端首次启动时向服务端注册，获得 ``device_id`` + ``token``。
- 之后连接时携带 ``token``，服务端校验通过才允许建立连接。
- token 用 ``secrets.token_hex`` 生成，足够随机不可猜测。

持久化：内存 dict 为一级缓存，可选的 ``DeviceTokenStore``（MySQL）为持久层——
注册时写穿透（write-through），内存未命中时读穿透（read-through）。
服务重启后已注册设备的 token 依旧有效，避免客户端旧 token 失效导致的
4004（unauthorized）重连循环。MySQL 不可达时自动降级为纯内存模式
（注册/鉴权照常，重启后设备需重新注册）。
"""

from __future__ import annotations

import logging
import secrets
import time
from dataclasses import dataclass
from typing import TYPE_CHECKING, Optional

if TYPE_CHECKING:
    from app.core.device_token_store import DeviceTokenStore

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
    """设备注册表（内存缓存 + 可选 DB 持久化，接口可替换）。"""

    def __init__(self, token_ttl: int = 0, store: "DeviceTokenStore | None" = None) -> None:
        self._devices: dict[str, Device] = {}
        self._token_ttl = token_ttl
        # 持久层（可为 None = 纯内存模式）
        self._store = store

    def load_persisted(self) -> None:
        """启动时从持久层预热内存缓存（MySQL 不可达则降级纯内存）。"""
        if self._store is None:
            return
        try:
            rows = self._store.load_all()
        except Exception:
            logger.warning("设备 token 持久层不可达，本次启动降级为纯内存模式", exc_info=True)
            return
        for device_id, row in rows.items():
            self._devices[device_id] = Device(
                device_id=device_id,
                token=row["token"],
                created_at=row["created_at"],
                last_seen_at=row["last_seen_at"],
                device_name=row.get("device_name") or device_id,
            )
        logger.info("设备 token 预热完成：内存 %d 台", len(self._devices))

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
        if self._store is not None:
            try:
                self._store.upsert(device_id, token, device.device_name, now, now)  # 写穿透
            except Exception:
                # 持久层故障不阻断注册：本次 token 仍在内存有效（重启后失效）
                logger.warning("设备 token 持久化失败（本次仅内存有效）", exc_info=True)
        logger.info("设备注册成功：%s（昵称=%s）", device_id, device.device_name)
        return device

    def update_name(self, device_id: str, device_name: str) -> Optional[Device]:
        """更新设备昵称（不刷新 token）。设备不存在返回 None。"""
        device = self._devices.get(device_id)
        if device is None:
            return None
        if device_name:
            device.device_name = device_name
            if self._store is not None:
                try:
                    self._store.update_name(device_id, device_name)  # 写穿透
                except Exception:
                    logger.warning("设备昵称持久化失败（仅内存更新）", exc_info=True)
        return device

    def authenticate(self, device_id: str, token: str) -> bool:
        """校验 device_id + token 是否匹配且未过期。"""
        device = self._devices.get(device_id)
        if device is None:
            # 内存未命中：读穿透（服务重启后预热可能失败，或并发下缓存未及同步）
            if self._store is not None:
                try:
                    row = self._store.get(device_id)
                except Exception:
                    logger.warning("设备 token 持久层查询失败，按未命中处理", exc_info=True)
                    return False
                if row is not None:
                    device = Device(
                        device_id=device_id,
                        token=row["token"],
                        created_at=row["created_at"],
                        last_seen_at=row["last_seen_at"],
                        device_name=row.get("device_name") or device_id,
                    )
                    self._devices[device_id] = device  # 回填内存缓存
            if device is None:
                return False
        # token 常量时间比较，避免时序攻击
        if not secrets.compare_digest(device.token, token):
            return False
        if self._token_ttl > 0:
            if time.time() - device.last_seen_at > self._token_ttl:
                return False
        now = time.time()
        device.last_seen_at = now
        if self._store is not None:
            try:
                self._store.touch_last_seen(device_id, now)  # 写穿透（刷新活跃时间）
            except Exception:
                pass  # 刷新失败无碍，仅影响 token_ttl 跨重启判定
        return True

    def get(self, device_id: str) -> Optional[Device]:
        return self._devices.get(device_id)

    def list_devices(self) -> list[Device]:
        """列出所有已注册设备（按 device_id 排序）。"""
        return [self._devices[k] for k in sorted(self._devices.keys())]

    def remove(self, device_id: str) -> None:
        self._devices.pop(device_id, None)
        if self._store is not None:
            try:
                self._store.delete(device_id)  # 写穿透
            except Exception:
                logger.warning("设备删除持久化失败（仅内存删除）", exc_info=True)

    def count(self) -> int:
        return len(self._devices)
