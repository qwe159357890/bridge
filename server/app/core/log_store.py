"""日志存储层（MySQL 持久化，不可用时降级内存）。

客户端把诊断日志文本 POST 到服务端，供后台排查问题。

存储模型：``device_id -> 日志条目列表``（MySQL 表 ``device_logs``）。
每次上传追加一条 LogEntry，保留最近 ``max_entries_per_device`` 条
（防止单设备无限堆积）。

持久化策略（2026-10-29 拍板）：
- 默认走 MySQL（与位置/短信/通话/SIM 等一致），服务重启不丢；
- MySQL 不可达或未配置时**自动降级纯内存**（接口行为不变，重启丢失但服务不受影响）；
- 接口与历史内存版完全一致，调用方（api/logs.py）零改动。

构建方式：
- 测试 / 纯内存：``LogStore(max_entries_per_device=5, max_content_chars=1000)``
  （host 未提供 → 内存模式）；
- 生产：``build_log_store(settings.locations.mysql)``（有效 host → MySQL 持久化）。
"""

from __future__ import annotations

import logging
import time
from dataclasses import dataclass, field
from typing import Optional

logger = logging.getLogger(__name__)

_CLEANUP_INTERVAL = 3600.0  # 保留天数清理周期（秒），add 时低频触发


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
    """日志存储（MySQL 持久化，不可用/未配置时降级内存）。

    :param max_entries_per_device: 单设备保留的最大日志条数（0 = 不限）。
        默认 2000（兜底），主清理策略是 retention_days 按天清理；
        见 config.LogsConfig 说明。
    :param max_content_chars: 单条日志内容最大字符数（超长截断尾部，防请求体过大）
    :param retention_days: 日志保留天数，超龄定期清理（0 = 永不清理，仅按条数上限裁剪）
    :param host/port/user/password/database: MySQL 连接信息；host 为空时纯内存模式
    """

    _CREATE_TABLE_SQL = """
    CREATE TABLE IF NOT EXISTS device_logs (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        device_id VARCHAR(64) NOT NULL,
        device_name VARCHAR(128) NOT NULL DEFAULT '',
        app_version VARCHAR(64) NOT NULL DEFAULT '',
        content LONGTEXT NOT NULL,
        created_at DOUBLE NOT NULL,
        INDEX idx_device_created (device_id, created_at)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """

    def __init__(
        self,
        max_entries_per_device: int = 2000,
        max_content_chars: int = 200_000,
        retention_days: int = 30,
        host: str = "",
        port: int = 3306,
        user: str = "root",
        password: str = "",
        database: str = "bridge",
    ) -> None:
        self._max_entries = max_entries_per_device
        self._max_content = max_content_chars
        self._retention_days = retention_days
        self._last_cleanup = 0.0
        # MySQL 连接信息；host 为空 = 纯内存模式（测试/无 MySQL 环境）
        self._mysql_host = host
        self._mysql_args = (host, port, user, password, database)
        self._conn = None  # pymysql 连接（惰性建立）
        self._mysql_enabled = bool(host)  # 是否启用 MySQL（host 非空才启用）
        self._mysql_ok = False  # 最近一次 MySQL 操作是否成功（降级/恢复开关）
        # 内存降级缓冲（始终保留，MySQL 失败时兜底；纯内存模式即主存储）
        self._memory: dict[str, list[LogEntry]] = {}

    # ------------------------------------------------------------------
    # MySQL 连接管理（照 connection_event_store 的 ping+reconnect 模式）
    # ------------------------------------------------------------------

    def _connection(self):
        import pymysql

        if not self._mysql_enabled:
            raise RuntimeError("MySQL 未配置")
        if self._conn is not None:
            try:
                self._conn.ping(reconnect=True)
                return self._conn
            except Exception:
                self._close_quietly()
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
        with conn.cursor() as cur:
            cur.execute(self._CREATE_TABLE_SQL)
        conn.commit()
        self._conn = conn
        return conn

    def _close_quietly(self) -> None:
        if self._conn is not None:
            try:
                self._conn.close()
            except Exception:
                pass
        self._conn = None

    def connect(self) -> None:
        """预连 MySQL（启动时调用；未配置或失败不抛，端点层降级内存）。"""
        if not self._mysql_enabled:
            return
        try:
            self._connection()
            self._mysql_ok = True
            logger.info("日志存储已连接 MySQL")
        except Exception as exc:
            self._mysql_ok = False
            logger.warning("日志存储 MySQL 不可用，降级内存模式：%s", exc)

    def close(self) -> None:
        self._close_quietly()

    # ------------------------------------------------------------------
    # 对外接口（签名与历史内存版完全一致）
    # ------------------------------------------------------------------

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
            device_name=device_name or "",
            app_version=app_version or "",
        )
        # 写 MySQL；失败降级内存
        if self._mysql_enabled:
            try:
                conn = self._connection()
                with conn.cursor() as cur:
                    cur.execute(
                        "INSERT INTO device_logs "
                        "(device_id, device_name, app_version, content, created_at) "
                        "VALUES (%s, %s, %s, %s, %s)",
                        (device_id, device_name or "", app_version or "", text, entry.created_at),
                    )
                    # 超限清理：删除该设备最旧的、超出保留条数的记录
                    if self._max_entries > 0:
                        cur.execute(
                            "DELETE FROM device_logs WHERE id IN ("
                            "  SELECT id FROM ("
                            "    SELECT id FROM device_logs WHERE device_id = %s "
                            "    ORDER BY created_at DESC, id DESC LIMIT 18446744073709551615 OFFSET %s"
                            "  ) AS t"
                            ")",
                            (device_id, self._max_entries),
                        )
                conn.commit()
                self._mysql_ok = True
                self._maybe_cleanup()  # 低频按保留天数清理超龄日志
                logger.info("设备 %s 上传日志（%d 字，MySQL）", device_id, len(text))
                return entry
            except Exception as exc:
                if self._mysql_ok:
                    logger.warning("日志写入 MySQL 失败，降级内存：%s", exc)
                self._mysql_ok = False

        # 内存兜底
        entries = self._memory.setdefault(device_id, [])
        entries.append(entry)
        if self._max_entries > 0 and len(entries) > self._max_entries:
            del entries[: len(entries) - self._max_entries]
        logger.info("设备 %s 上传日志（%d 字，内存）", device_id, len(text))
        return entry

    def list(self, device_id: str, offset: int = 0, limit: int = 0) -> list[LogEntry]:
        """某设备的日志（由旧到新）。

        :param offset: 分页起始（0 起）；limit=0 表示不分页、返回全部
        :param limit: 每页条数（0 = 不分页）
        """
        if self._mysql_enabled:
            try:
                return self._list_mysql(device_id, offset, limit)
            except Exception as exc:
                if self._mysql_ok:
                    logger.warning("日志读取 MySQL 失败，读内存：%s", exc)
                self._mysql_ok = False
        entries = list(self._memory.get(device_id, []))
        if limit and limit > 0:
            return entries[offset:offset + limit]
        return entries

    def _list_mysql(self, device_id: str, offset: int, limit: int) -> list[LogEntry]:
        conn = self._connection()
        sql = (
            "SELECT device_id, device_name, app_version, content, created_at "
            "FROM device_logs WHERE device_id = %s ORDER BY created_at ASC, id ASC"
        )
        args: list = [device_id]
        if limit and limit > 0:
            sql += " LIMIT %s OFFSET %s"
            args += [limit, offset]
        with conn.cursor() as cur:
            cur.execute(sql, args)
            rows = cur.fetchall()
        self._mysql_ok = True
        return [
            LogEntry(
                device_id=r[0],
                device_name=r[1],
                app_version=r[2],
                content=r[3],
                created_at=float(r[4]),
            )
            for r in rows
        ]

    def get_latest(self, device_id: str) -> Optional[LogEntry]:
        """某设备最新一条日志。"""
        entries = self.list(device_id)
        return entries[-1] if entries else None

    def clear(self, device_id: str) -> int:
        """清除某设备日志，返回清除的条数。"""
        removed = 0
        if self._mysql_enabled:
            try:
                conn = self._connection()
                with conn.cursor() as cur:
                    removed = int(cur.execute(
                        "DELETE FROM device_logs WHERE device_id = %s", (device_id,)
                    ))
                conn.commit()
                self._mysql_ok = True
                logger.info("清除设备 %s 的日志（%d 条，MySQL）", device_id, removed)
                return removed
            except Exception as exc:
                if self._mysql_ok:
                    logger.warning("清除日志 MySQL 失败，清内存：%s", exc)
                self._mysql_ok = False
        removed = len(self._memory.pop(device_id, []))
        logger.info("清除设备 %s 的日志（%d 条，内存）", device_id, removed)
        return removed

    def devices(self) -> list[str]:
        """列出有日志的设备 id。"""
        if self._mysql_enabled:
            try:
                conn = self._connection()
                with conn.cursor() as cur:
                    cur.execute("SELECT DISTINCT device_id FROM device_logs")
                    ids = sorted(r[0] for r in cur.fetchall())
                self._mysql_ok = True
                return ids
            except Exception as exc:
                if self._mysql_ok:
                    logger.warning("列设备日志 MySQL 失败，读内存：%s", exc)
                self._mysql_ok = False
        return sorted(self._memory.keys())

    def device_summaries(self) -> list[dict]:
        """列出有日志设备的摘要（设备 id + 日志条数 + 最新日志时间 + 昵称）。

        供管理后台日志页左侧设备列表展示。
        """
        if self._mysql_enabled:
            try:
                return self._summaries_mysql()
            except Exception as exc:
                if self._mysql_ok:
                    logger.warning("日志摘要 MySQL 失败，读内存：%s", exc)
                self._mysql_ok = False

        result = []
        for device_id, entries in self._memory.items():
            latest = entries[-1] if entries else None
            result.append({
                "device_id": device_id,
                "log_count": len(entries),
                "last_log_at": latest.created_at if latest else None,
                "device_name": latest.device_name if latest else "",
            })
        result.sort(key=lambda x: x["last_log_at"] or 0, reverse=True)
        return result

    def _summaries_mysql(self) -> list[dict]:
        conn = self._connection()
        with conn.cursor() as cur:
            cur.execute(
                "SELECT d.device_id, d.log_count, d.last_log_at, "
                "  (SELECT l.device_name FROM device_logs l WHERE l.device_id = d.device_id "
                "   ORDER BY l.created_at DESC, l.id DESC LIMIT 1) AS device_name "
                "FROM ("
                "  SELECT device_id, COUNT(*) AS log_count, MAX(created_at) AS last_log_at "
                "  FROM device_logs GROUP BY device_id"
                ") d ORDER BY d.last_log_at DESC"
            )
            rows = cur.fetchall()
        self._mysql_ok = True
        return [
            {
                "device_id": r[0],
                "log_count": int(r[1]),
                "last_log_at": float(r[2]) if r[2] is not None else None,
                "device_name": r[3] or "",
            }
            for r in rows
        ]

    def count(self, device_id: Optional[str] = None) -> int:
        """日志条数统计（不传 device_id 时返回全部条目总数）。"""
        if self._mysql_enabled:
            try:
                conn = self._connection()
                with conn.cursor() as cur:
                    if device_id is not None:
                        cur.execute(
                            "SELECT COUNT(*) FROM device_logs WHERE device_id = %s",
                            (device_id,),
                        )
                    else:
                        cur.execute("SELECT COUNT(*) FROM device_logs")
                    n = int(cur.fetchone()[0])
                self._mysql_ok = True
                return n
            except Exception as exc:
                if self._mysql_ok:
                    logger.warning("日志统计 MySQL 失败，读内存：%s", exc)
                self._mysql_ok = False
        if device_id is not None:
            return len(self._memory.get(device_id, []))
        return sum(len(v) for v in self._memory.values())

    # ------------------------------------------------------------------
    # 保留天数清理
    # ------------------------------------------------------------------

    def _maybe_cleanup(self) -> None:
        """低频清理超龄日志（每小时最多一次；retention_days=0 表示永不清理）。

        仅 MySQL 模式生效；内存模式由条数上限兜底，无需按天清理。
        """
        if not self._mysql_enabled or self._retention_days <= 0:
            return
        now = time.time()
        if now - self._last_cleanup < _CLEANUP_INTERVAL:
            return
        self._last_cleanup = now
        try:
            conn = self._connection()
            cutoff = now - self._retention_days * 86400.0
            with conn.cursor() as cur:
                n = cur.execute(
                    "DELETE FROM device_logs WHERE created_at < %s", (cutoff,)
                )
            conn.commit()
            if n:
                logger.info("日志清理 %d 条超龄记录（保留 %d 天）", n, self._retention_days)
        except Exception as exc:
            logger.debug("日志清理跳过（MySQL 不可用）：%s", exc)


def build_log_store(mysql_cfg, retention_days: int = 30, max_entries_per_device: int = 2000) -> LogStore:
    """按配置组装日志存储实例（app/main.py 装配用）。"""
    return LogStore(
        host=mysql_cfg.host,
        port=mysql_cfg.port,
        user=mysql_cfg.user,
        password=mysql_cfg.password,
        database=mysql_cfg.database,
        retention_days=retention_days,
        max_entries_per_device=max_entries_per_device,
    )
