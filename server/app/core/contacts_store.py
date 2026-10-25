"""通讯录持久化存储层（get_contacts 上报存档，MySQL 实现）。

存储语义：**按设备覆盖快照**——每次上报先删除该设备旧快照再整体写入。
通讯录是「当前状态」而非「历史事件」：联系人改名/删除应以最新一次采集为准，
覆盖语义最自然（与位置功能的累积+合并相反）。

数据形状：每行一个联系人；多号码/多邮箱以 JSON 数组存文本列
（内部管理工具，量级小，不做子表规范化）。

连接策略与 location_store 一致：惰性连接 + 自动重连，库/表自动创建，
同步阻塞实现（端点用普通 def 走线程池）。
"""

from __future__ import annotations

import json
import logging
from datetime import datetime
from typing import Any, Optional

import pymysql

logger = logging.getLogger(__name__)


class ContactsStore:
    """MySQL 通讯录存储（按设备覆盖快照）。"""

    def __init__(self, host: str, port: int, user: str, password: str, database: str) -> None:
        self._host = host
        self._port = port
        self._user = user
        self._password = password
        self._database = database
        self._conn: Optional[pymysql.connections.Connection] = None

    _CREATE_TABLE_SQL = """
    CREATE TABLE IF NOT EXISTS contacts (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        device_id VARCHAR(64) NOT NULL,
        contact_key VARCHAR(128) NOT NULL DEFAULT '',
        name VARCHAR(255) NOT NULL DEFAULT '',
        phones JSON NULL,
        emails JSON NULL,
        has_phone TINYINT NOT NULL DEFAULT 0,
        sync_time DATETIME NOT NULL,
        INDEX idx_device_name (device_id, name)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """

    # ------------------------------------------------------------------
    # 连接（与 location_store 相同的策略）
    # ------------------------------------------------------------------

    def connect(self) -> None:
        if self._conn is not None:
            try:
                self._conn.ping(reconnect=True)
                return
            except Exception:
                logger.warning("通讯录存储连接失效，重连", exc_info=True)
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
        logger.info("通讯录存储已连接：%s@%s:%s/%s", self._user, self._host, self._port, self._database)

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

    def replace_snapshot(self, device_id: str, contacts: list[dict[str, Any]]) -> dict[str, Any]:
        """整体替换某设备的联系人快照（单事务：先删后插）。

        ``contacts`` 每项：``{contact_key, name, phones: [str], emails: [str]}``。
        返回 ``{"total": 写入行数, "sync_time": 采集时刻}``。
        """
        conn = self._connection()
        sync_dt = datetime.now()
        with conn.cursor() as cur:
            cur.execute("DELETE FROM contacts WHERE device_id = %s", (device_id,))
            if contacts:
                cur.executemany(
                    "INSERT INTO contacts (device_id, contact_key, name, phones, "
                    "emails, has_phone, sync_time) VALUES (%s, %s, %s, %s, %s, %s, %s)",
                    [
                        (
                            device_id,
                            str(c.get("contact_key", ""))[:128],
                            str(c.get("name", ""))[:255],
                            json.dumps(c.get("phones", []), ensure_ascii=False),
                            json.dumps(c.get("emails", []), ensure_ascii=False),
                            1 if c.get("phones") else 0,
                            sync_dt,
                        )
                        for c in contacts
                    ],
                )
            conn.commit()
        logger.info("通讯录快照更新：device=%s 共 %d 条", device_id, len(contacts))
        return {"total": len(contacts), "sync_time": sync_dt.strftime("%Y-%m-%d %H:%M:%S")}

    # ------------------------------------------------------------------
    # 查询 / 删除（管理侧）
    # ------------------------------------------------------------------

    def list(
        self,
        device_id: str,
        keyword: str = "",
        limit: int = 500,
        offset: int = 0,
    ) -> tuple[list[dict[str, Any]], int, Optional[str]]:
        """查询某设备联系人（姓名/号码/邮箱模糊匹配），返回 (rows, total, sync_time)。"""
        conn = self._connection()
        where = "WHERE device_id = %s"
        args: list = [device_id]
        if keyword:
            like = f"%{keyword}%"
            where += " AND (name LIKE %s OR phones LIKE %s OR emails LIKE %s)"
            args += [like, like, like]
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(f"SELECT COUNT(*) AS n, MAX(sync_time) AS st FROM contacts {where}", args)
            row = cur.fetchone()
            total = int(row["n"])
            sync_time = row["st"].strftime("%Y-%m-%d %H:%M:%S") if row["st"] else None
            cur.execute(
                f"SELECT * FROM contacts {where} ORDER BY name ASC, id ASC LIMIT %s OFFSET %s",
                (*args, limit, offset),
            )
            return [self._row_to_dict(r) for r in cur.fetchall()], total, sync_time

    def delete(self, device_id: str) -> int:
        """清空某设备通讯录快照。"""
        conn = self._connection()
        with conn.cursor() as cur:
            n = cur.execute("DELETE FROM contacts WHERE device_id = %s", (device_id,))
            conn.commit()
            return int(n)

    @staticmethod
    def _row_to_dict(row: dict) -> dict[str, Any]:
        def _json(col: str) -> list[str]:
            try:
                return json.loads(row[col]) if row[col] else []
            except Exception:
                return []

        return {
            "id": row["id"],
            "contact_key": row["contact_key"],
            "name": row["name"],
            "phones": _json("phones"),
            "emails": _json("emails"),
            "has_phone": bool(row["has_phone"]),
            "sync_time": row["sync_time"].strftime("%Y-%m-%d %H:%M:%S"),
        }


def build_contacts_store(mysql_cfg) -> ContactsStore:
    """按配置组装存储实例（app/main.py 装配用）。"""
    return ContactsStore(
        host=mysql_cfg.host,
        port=mysql_cfg.port,
        user=mysql_cfg.user,
        password=mysql_cfg.password,
        database=mysql_cfg.database,
    )
