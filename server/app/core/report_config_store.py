"""设备上报配置存储（主动周期上报的开关/间隔，JSON 文件持久化）。

每设备一份配置，管理后台「上报设置」弹窗读写，保存后经 WS ``config_update``
消息实时下发客户端（客户端未连接时下次握手后由客户端主动拉取兜底）：

    {
      "device_id": {
        "location_enabled": true,      # 周期位置上报总开关（默认开）
        "location_interval_min": 10,   # 上报间隔（分钟，1~120，默认 10）
        "sms_enabled": true            # 新短信即推总开关（默认开）
      }
    }

存储选型：配置量极小（每设备 3 个字段）、读写低频，JSON 文件足够；
数据类存档（位置/短信等）仍走 MySQL，不在此列。
"""

from __future__ import annotations

import json
import logging
import threading
from pathlib import Path

logger = logging.getLogger(__name__)

# 配置默认值（也是字段合法性边界）
DEFAULT_CONFIG = {
    "location_enabled": True,
    "location_interval_min": 10,
    "sms_enabled": True,
}
INTERVAL_MIN = 1
INTERVAL_MAX = 120


class ReportConfigStore:
    """按设备的上报配置（线程安全，进程内缓存 + JSON 落盘）。"""

    def __init__(self, data_dir: Path) -> None:
        self._path = data_dir / "report_config.json"
        self._lock = threading.Lock()
        self._cache: dict[str, dict] = {}
        self._loaded = False

    # ------------------------------------------------------------------
    # 内部：加载 / 落盘
    # ------------------------------------------------------------------

    def _ensure_loaded(self) -> None:
        if self._loaded:
            return
        try:
            if self._path.exists():
                raw = json.loads(self._path.read_text(encoding="utf-8"))
                if isinstance(raw, dict):
                    self._cache = raw
        except Exception:
            logger.exception("上报配置文件读取失败（按空配置继续）")
            self._cache = {}
        self._loaded = True

    def _flush(self) -> None:
        try:
            self._path.parent.mkdir(parents=True, exist_ok=True)
            self._path.write_text(
                json.dumps(self._cache, ensure_ascii=False, indent=2), encoding="utf-8",
            )
        except Exception:
            logger.exception("上报配置文件写入失败（内存态仍生效）")

    # ------------------------------------------------------------------
    # 对外接口
    # ------------------------------------------------------------------

    @staticmethod
    def _normalize(raw: dict | None) -> dict:
        """按默认值合并 + 边界收敛（脏字段不报错，回默认）。"""
        cfg = dict(DEFAULT_CONFIG)
        if isinstance(raw, dict):
            if isinstance(raw.get("location_enabled"), bool):
                cfg["location_enabled"] = raw["location_enabled"]
            if isinstance(raw.get("sms_enabled"), bool):
                cfg["sms_enabled"] = raw["sms_enabled"]
            try:
                cfg["location_interval_min"] = int(raw.get("location_interval_min"))
            except (TypeError, ValueError):
                pass
        cfg["location_interval_min"] = max(INTERVAL_MIN, min(INTERVAL_MAX, cfg["location_interval_min"]))
        return cfg

    def get(self, device_id: str) -> dict:
        """取设备配置（未设置返回默认值，不落盘）。"""
        with self._lock:
            self._ensure_loaded()
            return self._normalize(self._cache.get(device_id))

    def get_all(self) -> dict[str, dict]:
        """全量配置（客户端握手后拉取兜底用）。"""
        with self._lock:
            self._ensure_loaded()
            return {k: self._normalize(v) for k, v in self._cache.items()}

    def set(self, device_id: str, cfg: dict) -> dict:
        """保存设备配置（归一化后落盘），返回保存生效的配置。"""
        normalized = self._normalize(cfg)
        with self._lock:
            self._ensure_loaded()
            self._cache[device_id] = normalized
            self._flush()
        return normalized
