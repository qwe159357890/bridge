"""连接事件存储层：记录设备的上下线历史（MySQL 持久化，2026-10-28）。

需求来源：管理后台要展示设备的上线下线时间轴（如「1 点上线 → 跑半小时 →
网络断掉线 → 2 点再上线 → 3 点下线」），需要把「上线登记」「下线回收」两个时机
各记一条事件。

存储模型：**MySQL 表**（``connection_events``，按保留天数定期清理）。
历史版本为内存环形列表（重启即丢，2026-10-28 用户拍板持久化）。
MySQL 不可达时**自动降级内存模式**（记录仍进内存、重启丢失但服务不受影响），
恢复后自动切回。

事件字段：
- device_id：设备标识
- event：``online`` / ``offline``
- reason：下线原因（closed=主动断开 / read_timeout=读超时 / replaced=被顶替 /
  server_kick=服务端踢 / reconnect=客户端重连等），上线时通常为空字符串
- ts：时间戳
"""

from __future__ import annotations

import logging
import time
from contextlib import contextmanager
from dataclasses import dataclass, field
from typing import Iterator

logger = logging.getLogger(__name__)

_CLEANUP_INTERVAL = 3600.0  # 保留天数清理周期（秒），record 时低频触发


@dataclass
class ConnectionEvent:
    """一条上下线事件。"""

    device_id: str
    event: str  # "online" | "offline"
    reason: str = ""
    ts: float = field(default_factory=time.time)

    def to_dict(self) -> dict:
        return {
            "device_id": self.device_id,
            "event": self.event,
            "reason": self.reason,
            "ts": self.ts,
        }


class ConnectionEventStore:
    """连接事件存储（MySQL 持久化，不可用时降级内存）。

    :param retention_days: 保留天数，超龄事件定期清理（0 = 永不清理）
    :param max_events: 内存降级模式的最多保留条数（0 = 不限）
    """

    _CREATE_TABLE_SQL = """
    CREATE TABLE IF NOT EXISTS connection_events (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        device_id VARCHAR(64) NOT NULL,
        event VARCHAR(16) NOT NULL,
        reason VARCHAR(64) NOT NULL DEFAULT '',
        ts DOUBLE NOT NULL,
        INDEX idx_device_ts (device_id, ts),
        INDEX idx_ts (ts)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """

    def __init__(
        self,
        host: str = "127.0.0.1",
        port: int = 3306,
        user: str = "root",
        password: str = "",
        database: str = "bridge",
        retention_days: int = 90,
        max_events: int = 500,
    ) -> None:
        self._mysql_args = (host, port, user, password, database)
        self._retention_days = retention_days
        self._max_events = max_events
        self._mysql_ok = False  # 最近一次 MySQL 操作是否成功（降级/恢复开关）
        self._memory: list[ConnectionEvent] = []  # 降级模式的事件缓冲
        self._last_cleanup = 0.0

    # ---- MySQL 连接管理（每操作独立连接，线程安全） ----

    def _new_connection(self):
        import pymysql

        host, port, user, password, database = self._mysql_args
        conn = pymysql.connect(
            host=host, port=port, user=user, password=password, charset="utf8mb4",
            connect_timeout=3,
        )
        with conn.cursor() as cur:
            cur.execute(
                f"CREATE DATABASE IF NOT EXISTS `{database}` "
                "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
            )
        conn.select_db(database)
        return conn

    @contextmanager
    def _connection(self) -> Iterator:
        """每操作独立连接（线程安全），用完即关，避免并发复用同一 socket。"""
        conn = self._new_connection()
        try:
            with conn.cursor() as cur:
                cur.execute(self._CREATE_TABLE_SQL)
            conn.commit()
            yield conn
        finally:
            try:
                conn.close()
            except Exception:
                pass

    # ---- 对外接口（签名与旧内存版完全一致，调用方零改动） ----

    def record(self, device_id: str, event: str, reason: str = "") -> ConnectionEvent:
        """记录一条事件。MySQL 失败时降级写内存。"""
        e = ConnectionEvent(device_id=device_id, event=event, reason=reason)
        try:
            with self._connection() as conn, conn.cursor() as cur:
                cur.execute(
                    "INSERT INTO connection_events (device_id, event, reason, ts) "
                    "VALUES (%s, %s, %s, %s)",
                    (device_id, event, reason, e.ts),
                )
                conn.commit()
            self._mysql_ok = True
        except Exception as exc:
            if self._mysql_ok:
                logger.warning("上下线记录 MySQL 不可用，降级内存模式：%s", exc)
            self._mysql_ok = False
            self._memory.append(e)
            if self._max_events > 0 and len(self._memory) > self._max_events:
                del self._memory[: len(self._memory) - self._max_events]
        self._maybe_cleanup()
        logger.info("连接事件 %s: device=%s reason=%s", event, device_id, reason or "-")
        return e

    def list(self, device_id: str | None = None, limit: int = 100) -> list[dict]:
        """列出事件（可按设备过滤），按时间倒序（最新在前）。"""
        try:
            return self._list_mysql(device_id, limit)
        except Exception as exc:
            logger.warning("上下线记录读取 MySQL 失败，读内存缓冲：%s", exc)
            self._mysql_ok = False
            return self._list_memory(device_id, limit)

    def _list_mysql(self, device_id: str | None, limit: int) -> list[dict]:
        conds, args = [], []
        if device_id is not None:
            conds.append("device_id = %s")
            args.append(device_id)
        where = ("WHERE " + " AND ".join(conds)) if conds else ""
        with self._connection() as conn, conn.cursor() as cur:
            cur.execute(
                f"SELECT device_id, event, reason, ts FROM connection_events {where} "
                "ORDER BY ts DESC LIMIT %s",
                (*args, limit),
            )
            rows = cur.fetchall()
        self._mysql_ok = True
        return [
            {"device_id": r[0], "event": r[1], "reason": r[2], "ts": float(r[3])}
            for r in rows
        ]

    def _list_memory(self, device_id: str | None, limit: int) -> list[dict]:
        events = self._memory
        if device_id is not None:
            events = [e for e in events if e.device_id == device_id]
        return [e.to_dict() for e in reversed(events[-limit:])]

    def count(self) -> int:
        try:
            with self._connection() as conn, conn.cursor() as cur:
                cur.execute("SELECT COUNT(*) FROM connection_events")
                return int(cur.fetchone()[0])
        except Exception:
            return len(self._memory)

    # ---- 保留天数清理 ----

    def _maybe_cleanup(self) -> None:
        """低频清理超龄事件（每小时最多一次；retention_days=0 表示永不清理）。"""
        if self._retention_days <= 0:
            return
        now = time.time()
        if now - self._last_cleanup < _CLEANUP_INTERVAL:
            return
        self._last_cleanup = now
        try:
            cutoff = now - self._retention_days * 86400.0
            with self._connection() as conn, conn.cursor() as cur:
                n = cur.execute("DELETE FROM connection_events WHERE ts < %s", (cutoff,))
                conn.commit()
            if n:
                logger.info("上下线记录清理 %d 条超龄事件（保留 %d 天）", n, self._retention_days)
        except Exception as exc:
            logger.debug("上下线记录清理跳过（MySQL 不可用）：%s", exc)


def build_connection_event_store(mysql_cfg, retention_days: int = 90) -> ConnectionEventStore:
    """按配置组装存储实例（app/main.py 装配用）。"""
    return ConnectionEventStore(
        host=mysql_cfg.host,
        port=mysql_cfg.port,
        user=mysql_cfg.user,
        password=mysql_cfg.password,
        database=mysql_cfg.database,
        retention_days=retention_days,
    )
