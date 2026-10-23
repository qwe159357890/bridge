"""核心层包（连接管理器、设备注册、日志存储、状态机等，不依赖 FastAPI，可独立单测）。"""

from .connection_manager import ConnectionLimitError, ConnectionManager
from .device_registry import Device, DeviceRegistry
from .log_store import LogEntry, LogStore

__all__ = [
    "ConnectionLimitError",
    "ConnectionManager",
    "Device",
    "DeviceRegistry",
    "LogEntry",
    "LogStore",
]
