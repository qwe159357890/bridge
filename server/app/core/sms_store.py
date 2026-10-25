"""短信持久化存储层（get_sms 上报存档，MySQL 实现）。

存储语义：**累积存档**——短信有历史价值（尤其验证码/通知），不做覆盖；
去重键 = (device_id, sms_key)，sms_key 取设备端短信 ``_id``
（``_id=0`` 的异常情况由客户端退化为内容摘要）。
重复上报同一设备数据时只刷新已读标记与正文（设备端已读状态可能变化），
不入新行——与位置功能「静止合并」类似的防膨胀思路。

类型编码（Android Telephony.Sms 标准）：1 收件箱 / 2 已发送 / 3 草稿 /
4 发送中(outbox) / 5 发送失败 / 6 队列。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Any, Optional

import pymysql

logger = logging.getLogger(__name__)

SMS_TYPE_NAMES = {1: "收件箱", 2: "已发送", 3: "草稿", 4: "发送中", 5: "发送失败", 6: "队列"}


class SmsStore:
    """MySQL 短信存储（累积 + (device_id, sms_key) 去重）。"""

    def __init__(self, host: str, port: int, user: str, password: str, database: str) -> None:
        self._host = host
        self._port = port
        self._user = user
        self._password = password
        self._database = database
        self._conn: Optional[pymysql.connections.Connection] = None

    _CREATE_TABLE_SQL = """
    CREATE TABLE IF NOT EXISTS sms_messages (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        device_id VARCHAR(64) NOT NULL,
        sms_key VARCHAR(64) NOT NULL DEFAULT '',
        address VARCHAR(64) NOT NULL DEFAULT '',
        body TEXT,
        sms_type TINYINT NOT NULL DEFAULT 1,
        read_flag TINYINT NOT NULL DEFAULT 0,
        sim_slot TINYINT NOT NULL DEFAULT -1,
        sim_number VARCHAR(32) NOT NULL DEFAULT '',
        sms_date DATETIME NOT NULL,
        sync_time DATETIME NOT NULL,
        UNIQUE KEY uq_device_sms (device_id, sms_key),
        INDEX idx_device_date (device_id, sms_date)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """

    # 旧表迁移：v0.1.26 及之前的 sms_messages 没有 sim 两列，逐条补上
    _MIGRATE_COLUMNS = [
        ("sim_slot", "TINYINT NOT NULL DEFAULT -1 COMMENT '卡槽序号(0基, -1未知)'"),
        ("sim_number", "VARCHAR(32) NOT NULL DEFAULT '' COMMENT '该卡本机号码'"),
    ]

    # ------------------------------------------------------------------
    # 连接
    # ------------------------------------------------------------------

    def connect(self) -> None:
        if self._conn is not None:
            try:
                self._conn.ping(reconnect=True)
                return
            except Exception:
                logger.warning("短信存储连接失效，重连", exc_info=True)
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
            for col, ddl in self._MIGRATE_COLUMNS:
                try:
                    cur.execute(f"ALTER TABLE sms_messages ADD COLUMN {col} {ddl}")
                    logger.info("短信表迁移：新增列 %s", col)
                except pymysql.err.OperationalError as e:
                    if e.args and e.args[0] == 1060:  # Duplicate column：已存在
                        pass
                    else:
                        raise
        conn.commit()
        self._conn = conn
        logger.info("短信存储已连接：%s@%s:%s/%s", self._user, self._host, self._port, self._database)

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
    # 写入（批量去重）
    # ------------------------------------------------------------------

    def save_batch(self, device_id: str, messages: list[dict[str, Any]]) -> dict[str, Any]:
        """批量保存短信（INSERT，重复键则更新已读/正文/类型/卡信息）。

        ``messages`` 每项：``{sms_key, address, body, type, date_ms, read,
        sim_slot, sim_number}``（sim 两项可缺省，缺省按未知 -1/''）。
        重复上报时 sim 字段一并更新——客户端采集端升级后重新下发即可**回填**
        历史数据的卡信息。
        返回 ``{"received": 收到条数, "inserted": 新增, "updated": 更新}``。
        """
        if not messages:
            return {"received": 0, "inserted": 0, "updated": 0}
        conn = self._connection()
        sync_dt = datetime.now()
        rows = []
        for m in messages:
            date_ms = int(m.get("date_ms") or 0)
            sms_dt = datetime.fromtimestamp(date_ms / 1000.0) if date_ms > 0 else sync_dt
            rows.append(
                (
                    device_id,
                    str(m.get("sms_key", ""))[:64],
                    str(m.get("address", ""))[:64],
                    str(m.get("body", "")),
                    int(m.get("type") or 1),
                    1 if m.get("read") else 0,
                    int(m.get("sim_slot", -1)),
                    str(m.get("sim_number", ""))[:32],
                    sms_dt,
                    sync_dt,
                )
            )
        with conn.cursor() as cur:
            # 精确区分新增/更新：executemany 对 ON DUPLICATE 的受影响行数语义
            # 模糊（更新计 2），改用前后 COUNT 差值
            cur.execute(
                "SELECT COUNT(*) FROM sms_messages WHERE device_id = %s", (device_id,)
            )
            before = int(cur.fetchone()[0])
            cur.executemany(
                "INSERT INTO sms_messages (device_id, sms_key, address, body, "
                "sms_type, read_flag, sim_slot, sim_number, sms_date, sync_time) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s) "
                "ON DUPLICATE KEY UPDATE read_flag=VALUES(read_flag), "
                "body=VALUES(body), sms_type=VALUES(sms_type), "
                "sim_slot=VALUES(sim_slot), sim_number=VALUES(sim_number), "
                "sync_time=VALUES(sync_time)",
                rows,
            )
            cur.execute(
                "SELECT COUNT(*) FROM sms_messages WHERE device_id = %s", (device_id,)
            )
            after = int(cur.fetchone()[0])
            conn.commit()
        result = {
            "received": len(rows),
            "inserted": after - before,
            "updated": len(rows) - (after - before),
        }
        logger.info("短信批量入库：device=%s %s", device_id, result)
        return result

    # ------------------------------------------------------------------
    # 查询 / 删除（管理侧）
    # ------------------------------------------------------------------

    def list(
        self,
        device_id: str,
        sms_type: Optional[int] = None,
        keyword: str = "",
        before_ms: Optional[int] = None,
        limit: int = 100,
    ) -> tuple[list[dict[str, Any]], int]:
        """按条件查询（sms_date 倒序）。

        ``before_ms``：时间游标分页——取该时刻**之前**的记录（前端把上一页
        最旧一条的 sms_date 传回来即可翻页），对多批次累积数据比 offset 稳。
        """
        conn = self._connection()
        conds, args = ["device_id = %s"], [device_id]
        if sms_type is not None:
            conds.append("sms_type = %s")
            args.append(sms_type)
        if keyword:
            like = f"%{keyword}%"
            conds.append("(address LIKE %s OR body LIKE %s)")
            args += [like, like]
        if before_ms is not None:
            conds.append("sms_date < %s")
            args.append(datetime.fromtimestamp(before_ms / 1000.0))
        where = "WHERE " + " AND ".join(conds)
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(f"SELECT COUNT(*) AS n FROM sms_messages {where}", args)
            total = int(cur.fetchone()["n"])
            cur.execute(
                f"SELECT * FROM sms_messages {where} "
                f"ORDER BY sms_date DESC, id DESC LIMIT %s",
                (*args, limit),
            )
            return [self._row_to_dict(r) for r in cur.fetchall()], total

    def delete(self, device_id: str, row_id: Optional[int] = None) -> int:
        """手动清理：删某设备全部（或指定一条）。"""
        conn = self._connection()
        with conn.cursor() as cur:
            if row_id is not None:
                n = cur.execute(
                    "DELETE FROM sms_messages WHERE device_id=%s AND id=%s",
                    (device_id, row_id),
                )
            else:
                n = cur.execute("DELETE FROM sms_messages WHERE device_id = %s", (device_id,))
            conn.commit()
            return int(n)

    @staticmethod
    def _row_to_dict(row: dict) -> dict[str, Any]:
        return {
            "id": row["id"],
            "sms_key": row["sms_key"],
            "address": row["address"],
            "body": row["body"] or "",
            "sms_type": int(row["sms_type"]),
            "sms_type_name": SMS_TYPE_NAMES.get(int(row["sms_type"]), "未知"),
            "read": bool(row["read_flag"]),
            "sim_slot": int(row["sim_slot"]),
            "sim_number": row.get("sim_number", ""),
            "sms_date": row["sms_date"].strftime("%Y-%m-%d %H:%M:%S"),
            "sync_time": row["sync_time"].strftime("%Y-%m-%d %H:%M:%S"),
        }


def build_sms_store(mysql_cfg) -> SmsStore:
    """按配置组装存储实例（app/main.py 装配用）。"""
    return SmsStore(
        host=mysql_cfg.host,
        port=mysql_cfg.port,
        user=mysql_cfg.user,
        password=mysql_cfg.password,
        database=mysql_cfg.database,
    )
