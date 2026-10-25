"""通话记录持久化存储层（get_call_log 上报存档，MySQL 实现）。

存储语义：**按设备覆盖快照**（与通讯录一致）——设备端通话记录本身是
滚动窗口（用户/系统会清理），本地存档以最新一次采集为准。

类型编码（Android CallLog.Calls 标准）：1 呼入 / 2 呼出 / 3 未接 /
4 语音信箱 / 5 拒接 / 6 已拦截。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Optional

import pymysql

logger = logging.getLogger(__name__)

CALL_TYPE_NAMES = {1: "呼入", 2: "呼出", 3: "未接", 4: "语音信箱", 5: "拒接", 6: "已拦截"}


class CallLogStore:
    """MySQL 通话记录存储（按设备覆盖快照）。"""

    def __init__(self, host: str, port: int, user: str, password: str, database: str) -> None:
        self._host = host
        self._port = port
        self._user = user
        self._password = password
        self._database = database
        self._conn: Optional[pymysql.connections.Connection] = None

    _CREATE_TABLE_SQL = """
    CREATE TABLE IF NOT EXISTS call_logs (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        device_id VARCHAR(64) NOT NULL,
        call_key VARCHAR(128) NOT NULL DEFAULT '',
        number VARCHAR(64) NOT NULL DEFAULT '',
        cached_name VARCHAR(255) NOT NULL DEFAULT '',
        call_type TINYINT NOT NULL DEFAULT 1,
        duration INT NOT NULL DEFAULT 0,
        call_time DATETIME NOT NULL,
        location VARCHAR(255) NOT NULL DEFAULT '',
        sync_time DATETIME NOT NULL,
        INDEX idx_device_time (device_id, call_time)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """

    # ------------------------------------------------------------------
    # 连接
    # ------------------------------------------------------------------

    def connect(self) -> None:
        if self._conn is not None:
            try:
                self._conn.ping(reconnect=True)
                return
            except Exception:
                logger.warning("通话记录存储连接失效，重连", exc_info=True)
                self._close_quietly()

        conn = pymysql.connect(
            host=self._host, port=self._port, user=self._user,
            password=self._password, charset="utf8mb4",
            connect_timeout=10,
        )
        with conn.cursor() as cur:
            cur.execute(
                f"CREATE DATABASE IF NOT EXISTS `{self._database}` "
                "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
            )
        conn.select_db(self._database)
        with conn.cursor() as cur:
            cur.execute(self._CREATE_TABLE_SQL)
        conn.commit()
        self._conn = conn
        logger.info("通话记录存储已连接：%s@%s:%s/%s", self._user, self._host, self._port, self._database)

    def _connection(self) -> pymysql.connections.Connection:
        self.connect()
        assert self._conn is not None
        return self._conn

    def _close_quietly(self) -> None:
        if self._conn is not None:
            try:
                self._conn.close()
            except Exception:
                pass
            self._conn = None

    def close(self) -> None:
        self._close_quietly()

    # ------------------------------------------------------------------
    # 写入（覆盖快照）
    # ------------------------------------------------------------------

    def replace_snapshot(self, device_id: str, records: list[dict[str, Any]]) -> dict[str, Any]:
        """整体替换某设备通话记录快照（单事务：先删后插）。

        ``records`` 每项：``{call_key, number, name, type, duration, date_ms, location}``。
        """
        conn = self._connection()
        sync_dt = datetime.now()
        with conn.cursor() as cur:
            cur.execute("DELETE FROM call_logs WHERE device_id = %s", (device_id,))
            if records:
                cur.executemany(
                    "INSERT INTO call_logs (device_id, call_key, number, cached_name, "
                    "call_type, duration, call_time, location, sync_time) "
                    "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s)",
                    [
                        (
                            device_id,
                            str(r.get("call_key", ""))[:128],
                            str(r.get("number", ""))[:64],
                            str(r.get("name", ""))[:255],
                            int(r.get("type") or 1),
                            int(r.get("duration") or 0),
                            datetime.fromtimestamp(int(r.get("date_ms") or 0) / 1000.0)
                            if int(r.get("date_ms") or 0) > 0 else sync_dt,
                            str(r.get("location", ""))[:255],
                            sync_dt,
                        )
                        for r in records
                    ],
                )
            conn.commit()
        logger.info("通话记录快照更新：device=%s 共 %d 条", device_id, len(records))
        return {"total": len(records), "sync_time": sync_dt.strftime("%Y-%m-%d %H:%M:%S")}

    # ------------------------------------------------------------------
    # 查询 / 删除（管理侧）
    # ------------------------------------------------------------------

    def list(
        self,
        device_id: str,
        call_type: Optional[int] = None,
        keyword: str = "",
        limit: int = 200,
        offset: int = 0,
    ) -> tuple[list[dict[str, Any]], int, Optional[str]]:
        """查询某设备通话记录（号码/联系人模糊匹配），返回 (rows, total, sync_time)。"""
        conn = self._connection()
        conds, args = ["device_id = %s"], [device_id]
        if call_type is not None:
            conds.append("call_type = %s")
            args.append(call_type)
        if keyword:
            like = f"%{keyword}%"
            conds.append("(number LIKE %s OR cached_name LIKE %s)")
            args += [like, like]
        where = "WHERE " + " AND ".join(conds)
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(f"SELECT COUNT(*) AS n, MAX(sync_time) AS st FROM call_logs {where}", args)
            row = cur.fetchone()
            total = int(row["n"])
            sync_time = row["st"].strftime("%Y-%m-%d %H:%M:%S") if row["st"] else None
            cur.execute(
                f"SELECT * FROM call_logs {where} ORDER BY call_time DESC, id DESC "
                f"LIMIT %s OFFSET %s",
                (*args, limit, offset),
            )
            return [self._row_to_dict(r) for r in cur.fetchall()], total, sync_time

    def delete(self, device_id: str) -> int:
        """清空某设备通话记录快照。"""
        conn = self._connection()
        with conn.cursor() as cur:
            n = cur.execute("DELETE FROM call_logs WHERE device_id = %s", (device_id,))
            conn.commit()
            return int(n)

    @staticmethod
    def _row_to_dict(row: dict) -> dict[str, Any]:
        return {
            "id": row["id"],
            "call_key": row["call_key"],
            "number": row["number"],
            "name": row["cached_name"],
            "call_type": int(row["call_type"]),
            "call_type_name": CALL_TYPE_NAMES.get(int(row["call_type"]), "未知"),
            "duration": int(row["duration"]),
            "call_time": row["call_time"].strftime("%Y-%m-%d %H:%M:%S"),
            "location": row["location"],
            "sync_time": row["sync_time"].strftime("%Y-%m-%d %H:%M:%S"),
        }


def build_calllog_store(mysql_cfg) -> CallLogStore:
    """按配置组装存储实例（app/main.py 装配用）。"""
    return CallLogStore(
        host=mysql_cfg.host,
        port=mysql_cfg.port,
        user=mysql_cfg.user,
        password=mysql_cfg.password,
        database=mysql_cfg.database,
    )
