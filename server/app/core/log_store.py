"""日志存储层（MySQL 持久化，不可用时降级内存）。

客户端把诊断日志上传到服务端，供后台排查问题。

存储模型（2026-10-29 重构为按行存储）：
- 每一条日志 = 一行，含 ``ts``(epoch 毫秒) / ``level`` / ``tag`` / ``message``；
- ``device_logs`` 表每条记录即一行日志（不再是一整块 content 大文本）；
- 按设备累积，支持按行分页、按 level/tag/关键字过滤。

持久化策略（2026-10-29 拍板）：
- 默认走 MySQL（与位置/短信/通话/SIM 等一致），服务重启不丢；
- MySQL 不可达或未配置时**自动降级纯内存**（接口行为不变，重启丢失但服务不受影响）。

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
    """一行设备日志。"""

    device_id: str
    message: str
    level: str = "I"
    tag: str = ""
    ts: float = field(default_factory=lambda: time.time() * 1000.0)  # epoch 毫秒
    device_name: str = ""
    app_version: str = ""
    created_at: float = field(default_factory=time.time)  # 入库时间（epoch 秒）

    def to_dict(self) -> dict:
        return {
            "device_id": self.device_id,
            "ts": self.ts,
            "level": self.level,
            "tag": self.tag,
            "message": self.message,
            "device_name": self.device_name,
            "app_version": self.app_version,
            "created_at": self.created_at,
        }


class LogStore:
    """日志存储（MySQL 持久化，不可用/未配置时降级内存）。

    :param max_entries_per_device: 单设备保留的最大日志行数（0 = 不限）。
        默认 2000（兜底），主清理策略是 retention_days 按天清理；
        见 config.LogsConfig 说明。
    :param max_content_chars: 单行日志消息最大字符数（超长截断尾部，防请求体过大）
    :param retention_days: 日志保留天数，超龄定期清理（0 = 永不清理，仅按行数上限裁剪）
    :param host/port/user/password/database: MySQL 连接信息；host 为空时纯内存模式
    """

    _CREATE_TABLE_SQL = """
    CREATE TABLE IF NOT EXISTS device_logs (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        device_id VARCHAR(64) NOT NULL,
        device_name VARCHAR(128) NOT NULL DEFAULT '',
        app_version VARCHAR(64) NOT NULL DEFAULT '',
        ts DOUBLE NOT NULL,
        level VARCHAR(8) NOT NULL DEFAULT 'I',
        tag VARCHAR(64) NOT NULL DEFAULT '',
        message TEXT NOT NULL,
        content LONGTEXT NOT NULL,
        line_hash CHAR(32) NOT NULL DEFAULT '',
        created_at DOUBLE NOT NULL,
        INDEX idx_device_ts (device_id, ts),
        INDEX idx_device_level (device_id, level),
        UNIQUE KEY uk_device_line (device_id, ts, line_hash)
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
            self._ensure_columns()
            self._migrate_legacy_content()
            self._mysql_ok = True
            logger.info("日志存储已连接 MySQL")
            # 启动时立即清理一次超龄日志（设备可能已长期停止上传，add 触发不到的兜底）
            self._cleanup_expired(force=True)
        except Exception as exc:
            self._mysql_ok = False
            logger.warning("日志存储 MySQL 不可用，降级内存模式：%s", exc)

    def _ensure_columns(self) -> None:
        """旧表补列（兼容 2026-10-29 之前按整块 content 存储的表结构）。

        旧表只有 content 列，没有 ts/level/tag/message/line_hash；这里逐列补齐
        （MySQL 对已存在列会抛 Duplicate column，需捕获忽略）。
        唯一索引 uk_device_line 若旧表缺失也会补齐（已存在则忽略）。
        """
        if not self._mysql_enabled:
            return
        conn = self._connection()
        cols = {
            "ts": "DOUBLE NOT NULL DEFAULT 0",
            "level": "VARCHAR(8) NOT NULL DEFAULT 'I'",
            "tag": "VARCHAR(64) NOT NULL DEFAULT ''",
            "message": "TEXT NOT NULL",
            "line_hash": "CHAR(32) NOT NULL DEFAULT ''",
        }
        with conn.cursor() as cur:
            for name, ddl in cols.items():
                try:
                    cur.execute(f"ALTER TABLE device_logs ADD COLUMN {name} {ddl}")
                except Exception:
                    # 列已存在，忽略
                    pass
            # 唯一索引去重是本功能的关键，创建失败绝不能静默吞掉——
            # 否则日志会大量重复入库、且无任何告警。这里显式检查并告警。
            try:
                cur.execute(
                    "ALTER TABLE device_logs "
                    "ADD UNIQUE KEY uk_device_line (device_id, ts, line_hash)"
                )
            except Exception as exc:
                # 常见原因：表里已有历史重复数据导致 Duplicate entry。
                # 此时必须先清重再补索引，否则 INSERT IGNORE 去重永远失效。
                logger.warning(
                    "日志表唯一索引 uk_device_line 创建失败，去重将失效：%s。"
                    "请先清理 device_logs 中的重复行再重建索引。", exc,
                )
            else:
                logger.info("日志表唯一索引 uk_device_line 已就绪，按行去重生效")
        conn.commit()

    def close(self) -> None:
        self._close_quietly()

    def _migrate_legacy_content(self) -> None:
        """迁移旧格式数据：把整块 content 记录按行拆分为结构化行。

        2026-10-29 之前的记录只有整块 content（无 ts/level/tag/message 拆分），
        补列后 message 为空。这里把「message 为空但 content 非空」的旧记录，
        先删除原记录，再按行解析后 INSERT 回结构化行。
        ts 用 created_at（epoch 秒 × 1000）+ 行内偏移，保证时间单调递增、避免唯一键冲突。
        迁移只执行一次（迁移后 message 非空，不再命中条件）。
        """
        if not self._mysql_enabled:
            return
        conn = self._connection()
        try:
            with conn.cursor() as cur:
                cur.execute(
                    "SELECT id, device_id, device_name, app_version, content, created_at "
                    "FROM device_logs WHERE message = '' AND content != ''"
                )
                rows = cur.fetchall()

            migrated = 0
            for r in rows:
                rid, device_id, device_name, app_version, content, created_at = r
                base_ts = created_at * 1000.0
                # 解析出所有结构化行
                parsed = []
                for raw in content.splitlines():
                    line = raw.rstrip()
                    if not line:
                        continue
                    level, tag, msg = _parse_line(line)
                    parsed.append((level, tag, msg))

                if not parsed:
                    continue

                with conn.cursor() as cur:
                    # 删除旧整块记录
                    cur.execute("DELETE FROM device_logs WHERE id = %s", (rid,))
                    # 逐行 INSERT（ts 用 base_ts + 偏移，保证单调 + 去重唯一键不冲突）
                    for i, (level, tag, msg) in enumerate(parsed):
                        ts = base_ts + i
                        cur.execute(
                            "INSERT IGNORE INTO device_logs "
                            "(device_id, device_name, app_version, ts, level, tag, message, content, line_hash, created_at) "
                            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)",
                            (device_id, device_name or "", app_version or "", ts, level,
                             tag, msg, msg, _line_hash(ts, level, tag, msg), created_at),
                        )
                migrated += 1

            conn.commit()
            if migrated:
                logger.info("日志迁移旧格式 %d 条记录为按行结构", migrated)
        except Exception as exc:
            logger.debug("日志旧数据迁移跳过：%s", exc)

    # ------------------------------------------------------------------
    # 对外接口
    # ------------------------------------------------------------------

    def add(
        self,
        device_id: str,
        content: str = "",
        device_name: str = "",
        app_version: str = "",
        logs: Optional[list[dict]] = None,
    ) -> int:
        """追加日志并入库，返回去重后实际新入库的行数（int）。

        - ``logs``：结构化日志行数组（新客户端），每项含 ts/level/tag/message；
        - ``content``：整块文本（旧客户端），服务端会按行拆分为 LogEntry 入库。

        超长内容截断尾部，超限条数丢弃最旧。重复行（同设备同 ts 同 hash）自动跳过。
        """
        entries = self._build_entries(device_id, content, device_name, app_version, logs)
        if not entries:
            return []

        if self._mysql_enabled:
            try:
                conn = self._connection()
                inserted = 0
                with conn.cursor() as cur:
                    for e in entries:
                        # INSERT IGNORE + 唯一索引 (device_id, ts, line_hash) 去重：
                        # 客户端全量上传时，已存在的历史行会被自动跳过，只落新行。
                        cur.execute(
                            "INSERT IGNORE INTO device_logs "
                            "(device_id, device_name, app_version, ts, level, tag, message, content, line_hash, created_at) "
                            "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s)",
                            (e.device_id, e.device_name, e.app_version, e.ts, e.level,
                             e.tag, e.message, e.message, _line_hash(e.ts, e.level, e.tag, e.message), e.created_at),
                        )
                        inserted += cur.rowcount
                    if self._max_entries > 0:
                        cur.execute(
                            "DELETE FROM device_logs WHERE id IN ("
                            "  SELECT id FROM ("
                            "    SELECT id FROM device_logs WHERE device_id = %s "
                            "    ORDER BY ts DESC, id DESC LIMIT 18446744073709551615 OFFSET %s"
                            "  ) AS t"
                            ")",
                            (device_id, self._max_entries),
                        )
                conn.commit()
                self._mysql_ok = True
                self._maybe_cleanup()
                logger.info("设备 %s 上传日志 %d 行，去重后新入库 %d 行（MySQL）",
                            device_id, len(entries), inserted)
                return inserted
            except Exception as exc:
                if self._mysql_ok:
                    logger.warning("日志写入 MySQL 失败，降级内存：%s", exc)
                self._mysql_ok = False

        # 内存兜底：用 (ts, level, tag, message) 元组去重
        buf = self._memory.setdefault(device_id, [])
        seen = {(e.ts, e.level, e.tag, e.message) for e in buf}
        new_entries = []
        for e in entries:
            key = (e.ts, e.level, e.tag, e.message)
            if key in seen:
                continue
            seen.add(key)
            buf.append(e)
            new_entries.append(e)
        if self._max_entries > 0 and len(buf) > self._max_entries:
            del buf[: len(buf) - self._max_entries]
        logger.info("设备 %s 上传日志 %d 行，去重后新入库 %d 行（内存）",
                    device_id, len(entries), len(new_entries))
        return len(new_entries)

    def _build_entries(
        self,
        device_id: str,
        content: str,
        device_name: str,
        app_version: str,
        logs: Optional[list[dict]],
    ) -> list[LogEntry]:
        """把结构化行数组 + 整块文本统一转成 LogEntry 列表（按行）。"""
        entries: list[LogEntry] = []
        # 1) 结构化行（新客户端）
        for line in (logs or []):
            msg = line.get("message", "")
            msg = msg if len(msg) <= self._max_content else msg[-self._max_content:]
            # ts 归一到整数毫秒（与 _line_hash 的 int(ts) 对齐，保证 hash 稳定）
            ts_raw = line.get("ts") or (time.time() * 1000.0)
            ts_norm = int(float(ts_raw))
            entries.append(LogEntry(
                device_id=device_id,
                message=msg,
                level=(line.get("level") or "I")[:8],
                tag=(line.get("tag") or "")[:64],
                ts=float(ts_norm),
                device_name=device_name or "",
                app_version=app_version or "",
            ))
        # 2) 整块文本（旧客户端兼容，已基本废弃）：按行拆分，简单解析 `[L] [tag] msg`
        # 已知限制：旧 content 无精确逐行时间戳，ts 用「当前时刻」兜底，
        # 因此旧客户端重复上传同一批 content 时 ts 不稳定、去重失效——
        # 这是过渡兼容路径的可接受限制（新客户端一律走 logs 数组，ts 精确）。
        if content:
            for raw in content.splitlines():
                line = raw.rstrip()
                if not line:
                    continue
                level, tag, msg = _parse_line(line)
                entries.append(LogEntry(
                    device_id=device_id,
                    message=msg,
                    level=level,
                    tag=tag,
                    device_name=device_name or "",
                    app_version=app_version or "",
                ))
        return entries

    def list(
        self,
        device_id: str,
        offset: int = 0,
        limit: int = 0,
        level: str = "",
        tag: str = "",
        keyword: str = "",
    ) -> list[LogEntry]:
        """某设备的日志（按时间由旧到新），支持过滤与分页。

        :param offset: 分页起始（0 起）；limit=0 表示不分页、返回全部
        :param level: 按级别过滤（I/W/E/D，空=全部）
        :param tag: 按模块标签过滤（空=全部）
        :param keyword: 按正文关键字模糊过滤（空=全部）
        """
        if self._mysql_enabled:
            try:
                return self._list_mysql(device_id, offset, limit, level, tag, keyword)
            except Exception as exc:
                if self._mysql_ok:
                    logger.warning("日志读取 MySQL 失败，读内存：%s", exc)
                self._mysql_ok = False
        entries = self._filter_memory(device_id, level, tag, keyword)
        if limit and limit > 0:
            return entries[offset:offset + limit]
        return entries

    def _filter_memory(self, device_id: str, level: str, tag: str, keyword: str) -> list[LogEntry]:
        entries = list(self._memory.get(device_id, []))
        if level:
            entries = [e for e in entries if e.level == level]
        if tag:
            entries = [e for e in entries if e.tag == tag]
        if keyword:
            entries = [e for e in entries if keyword in e.message]
        return entries

    def _list_mysql(
        self,
        device_id: str,
        offset: int,
        limit: int,
        level: str,
        tag: str,
        keyword: str,
    ) -> list[LogEntry]:
        conn = self._connection()
        sql = (
            "SELECT device_id, device_name, app_version, ts, level, tag, message, created_at "
            "FROM device_logs WHERE device_id = %s"
        )
        args: list = [device_id]
        if level:
            sql += " AND level = %s"
            args.append(level)
        if tag:
            sql += " AND tag = %s"
            args.append(tag)
        if keyword:
            sql += " AND message LIKE %s"
            args.append(f"%{keyword}%")
        sql += " ORDER BY ts ASC, id ASC"
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
                ts=float(r[3]),
                level=r[4],
                tag=r[5],
                message=r[6],
                created_at=float(r[7]),
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
        """列出有日志设备的摘要（设备 id + 日志行数 + 最新日志时间 + 昵称）。"""
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
                "last_log_at": latest.ts / 1000.0 if latest else None,
                "device_name": latest.device_name if latest else "",
            })
        result.sort(key=lambda x: x["last_log_at"] or 0, reverse=True)
        return result

    def _summaries_mysql(self) -> list[dict]:
        conn = self._connection()
        with conn.cursor() as cur:
            cur.execute(
                "SELECT d.device_id, d.log_count, d.last_ts, "
                "  (SELECT l.device_name FROM device_logs l WHERE l.device_id = d.device_id "
                "   ORDER BY l.ts DESC, l.id DESC LIMIT 1) AS device_name "
                "FROM ("
                "  SELECT device_id, COUNT(*) AS log_count, MAX(ts) AS last_ts "
                "  FROM device_logs GROUP BY device_id"
                ") d ORDER BY d.last_ts DESC"
            )
            rows = cur.fetchall()
        self._mysql_ok = True
        return [
            {
                "device_id": r[0],
                "log_count": int(r[1]),
                "last_log_at": float(r[2]) / 1000.0 if r[2] is not None else None,
                "device_name": r[3] or "",
            }
            for r in rows
        ]

    def count(
        self,
        device_id: Optional[str] = None,
        level: str = "",
        tag: str = "",
        keyword: str = "",
    ) -> int:
        """日志行数统计（不传 device_id 时返回全部行总数；支持 level/tag/keyword 过滤）。"""
        if self._mysql_enabled:
            try:
                conn = self._connection()
                sql = "SELECT COUNT(*) FROM device_logs"
                conds: list[str] = []
                args: list = []
                if device_id is not None:
                    conds.append("device_id = %s")
                    args.append(device_id)
                if level:
                    conds.append("level = %s")
                    args.append(level)
                if tag:
                    conds.append("tag = %s")
                    args.append(tag)
                if keyword:
                    conds.append("message LIKE %s")
                    args.append(f"%{keyword}%")
                if conds:
                    sql += " WHERE " + " AND ".join(conds)
                with conn.cursor() as cur:
                    cur.execute(sql, args)
                    n = int(cur.fetchone()[0])
                self._mysql_ok = True
                return n
            except Exception as exc:
                if self._mysql_ok:
                    logger.warning("日志统计 MySQL 失败，读内存：%s", exc)
                self._mysql_ok = False
        entries = self._memory.get(device_id, []) if device_id is not None else [
            e for v in self._memory.values() for e in v
        ]
        if level:
            entries = [e for e in entries if e.level == level]
        if tag:
            entries = [e for e in entries if e.tag == tag]
        if keyword:
            entries = [e for e in entries if keyword in e.message]
        return len(entries)

    # ------------------------------------------------------------------
    # 保留天数清理
    # ------------------------------------------------------------------

    def _maybe_cleanup(self) -> None:
        """低频清理超龄日志（每小时最多一次；retention_days=0 表示永不清理）。"""
        if not self._mysql_enabled or self._retention_days <= 0:
            return
        now = time.time()
        if now - self._last_cleanup < _CLEANUP_INTERVAL:
            return
        self._cleanup_expired(force=True)

    def _cleanup_expired(self, force: bool = False) -> None:
        """删除超龄日志（force=True 忽略节流立即执行）。

        除 add 时的低频触发外，服务启动（connect）也会 force 清理一次，
        避免设备停止上传后超龄数据长期滞留、表持续膨胀。
        """
        if not self._mysql_enabled or self._retention_days <= 0:
            return
        now = time.time()
        if not force and now - self._last_cleanup < _CLEANUP_INTERVAL:
            return
        self._last_cleanup = now
        try:
            conn = self._connection()
            cutoff_ms = (now - self._retention_days * 86400.0) * 1000.0
            with conn.cursor() as cur:
                n = cur.execute(
                    "DELETE FROM device_logs WHERE ts < %s", (cutoff_ms,)
                )
            conn.commit()
            if n:
                logger.info("日志清理 %d 条超龄记录（保留 %d 天）", n, self._retention_days)
        except Exception as exc:
            logger.debug("日志清理跳过（MySQL 不可用）：%s", exc)


def _line_hash(ts: float, level: str, tag: str, message: str) -> str:
    """计算一行日志的去重 hash（MD5，32 位十六进制）。

    用于唯一索引 (device_id, ts, line_hash) 去重：同一设备、同一毫秒、同一级别、
    同一 tag、同一正文的日志视为重复，全量上传时自动跳过。
    """
    import hashlib

    # ts 归一化为整数毫秒：客户端 LogLine.ts 是 Long（整数毫秒），但服务端经
    # pydantic float 解析后可能变成 float（如 169855....233.0），若不归一，
    # 同一条日志在「内存路径/磁盘路径/旧格式兜底」下字符串化不一致 → hash 漂移 → 去重失效。
    ts_norm = int(ts)
    raw = f"{ts_norm}|{level}|{tag}|{message}".encode("utf-8")
    return hashlib.md5(raw).hexdigest()


def _parse_line(line: str) -> tuple[str, str, str]:
    """解析一行日志 ``MM-dd HH:mm:ss.SSS [L] [tag] msg`` → (level, tag, message)。

    解析失败时整行作为 message、level 记 I、tag 为空。
    """
    try:
        # 找第一个 '[' 之后的结构
        first = line.find("[")
        if first < 0:
            return "I", "", line
        rest = line[first:]
        # rest 形如 "[L] [tag] msg" 或 "[L] msg"
        if not rest.startswith("["):
            return "I", "", line
        close = rest.find("]")
        if close < 0:
            return "I", "", line
        level = rest[1:close].strip()
        after = rest[close + 1:].lstrip()
        if after.startswith("["):
            c2 = after.find("]")
            if c2 > 0:
                tag = after[1:c2].strip()
                message = after[c2 + 1:].lstrip()
                return level, tag, message
            return level, "", after
        return level, "", after
    except Exception:
        return "I", "", line


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
