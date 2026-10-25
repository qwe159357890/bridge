"""SIM 卡信息持久化存储层（get_sim_info 上报存档，MySQL 实现）。

存储语义：**按设备覆盖快照**——每次 get_sim_info 整体替换该设备的卡列表。

用途：管理后台「发送短信/拨打电话」的选卡下拉按此结果动态显示
（卡数 / 本机号码 / 运营商），未上报过时退化为静态选项。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Optional

import pymysql

logger = logging.getLogger(__name__)


class SimStore:
    """MySQL SIM 卡信息存储（按设备覆盖快照）。"""

    def __init__(self, host: str, port: int, user: str, password: str, database: str) -> None:
        self._host = host
        self._port = port
        self._user = user
        self._password = password
        self._database = database
        self._conn: Optional[pymysql.connections.Connection] = None

    _CREATE_TABLE_SQL = """
    CREATE TABLE IF NOT EXISTS device_sim_info (
        device_id VARCHAR(64) NOT NULL,
        slot_index TINYINT NOT NULL,
        subscription_id INT NOT NULL DEFAULT 0,
        number VARCHAR(32) NOT NULL DEFAULT '',
        carrier VARCHAR(64) NOT NULL DEFAULT '',
        sync_time DATETIME NOT NULL,
        PRIMARY KEY (device_id, slot_index)
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
                logger.warning("SIM 卡存储连接失效，重连", exc_info=True)
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
        logger.info("SIM 卡存储已连接：%s@%s:%s/%s", self._user, self._host, self._port, self._database)

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

    def replace_snapshot(self, device_id: str, sims: list[dict[str, Any]]) -> dict[str, Any]:
        """整体替换某设备 SIM 卡快照（单事务：先删后插）。

        ``sims`` 每项：``{slot_index, subscription_id, number, carrier}``。
        """
        conn = self._connection()
        sync_dt = datetime.now()
        with conn.cursor() as cur:
            cur.execute("DELETE FROM device_sim_info WHERE device_id = %s", (device_id,))
            if sims:
                cur.executemany(
                    "INSERT INTO device_sim_info (device_id, slot_index, subscription_id, "
                    "number, carrier, sync_time) VALUES (%s, %s, %s, %s, %s, %s)",
                    [
                        (
                            device_id,
                            int(s.get("slot_index", 0)),
                            int(s.get("subscription_id", 0)),
                            str(s.get("number", ""))[:32],
                            str(s.get("carrier", ""))[:64],
                            sync_dt,
                        )
                        for s in sims
                    ],
                )
            conn.commit()
        logger.info("SIM 卡快照更新：device=%s 共 %d 张卡", device_id, len(sims))
        return {"total": len(sims), "sync_time": sync_dt.strftime("%Y-%m-%d %H:%M:%S")}

    # ------------------------------------------------------------------
    # 查询 / 删除（管理侧）
    # ------------------------------------------------------------------

    def list(self, device_id: str) -> tuple[list[dict[str, Any]], Optional[str]]:
        """查询某设备 SIM 卡列表（按卡槽序号排序），返回 (rows, sync_time)。"""
        conn = self._connection()
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(
                "SELECT * FROM device_sim_info WHERE device_id = %s ORDER BY slot_index",
                (device_id,),
            )
            rows = cur.fetchall()
            sync_time = (
                rows[0]["sync_time"].strftime("%Y-%m-%d %H:%M:%S") if rows else None
            )
            return [self._row_to_dict(r) for r in rows], sync_time

    def delete(self, device_id: str) -> int:
        """清空某设备 SIM 卡快照。"""
        conn = self._connection()
        with conn.cursor() as cur:
            n = cur.execute("DELETE FROM device_sim_info WHERE device_id = %s", (device_id,))
            conn.commit()
            return int(n)

    @staticmethod
    def _row_to_dict(row: dict) -> dict[str, Any]:
        return {
            "slot_index": int(row["slot_index"]),
            "subscription_id": int(row["subscription_id"]),
            "number": row["number"],
            "carrier": row["carrier"],
            "sync_time": row["sync_time"].strftime("%Y-%m-%d %H:%M:%S"),
        }


def build_sim_store(mysql_cfg) -> SimStore:
    """按配置组装存储实例（app/main.py 装配用）。"""
    return SimStore(
        host=mysql_cfg.host,
        port=mysql_cfg.port,
        user=mysql_cfg.user,
        password=mysql_cfg.password,
        database=mysql_cfg.database,
    )
