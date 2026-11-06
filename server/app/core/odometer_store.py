"""极空里程台账持久化存储层（统计页同步，MySQL 实现）。

数据来源：设备端「极空统计页」手动录入的里程快照（odometer / 里程台账）。
每条记录 = 用户输入的三轮车当前里程 + 录入瞬间保护板循环容量/总压，
服务端只做**留档与幂等 upsert**，不做任何业务计算（电耗曲线全部在设备端算）。

幂等规则：唯一键 ``(device_id, record_id)``（record_id 由设备端生成、
录制后不变）。重复上传同 id 记录走 ``ON DUPLICATE KEY UPDATE`` 覆盖，
不产生重复行——设备端「同步 N 次」安全。

删除规则：**墓碑软删**——设备端删除「已同步」记录时，重传该 id 的
``deleted=1`` 墓碑行，服务端置位 ``deleted``；不物理删除，保留审计痕迹，
管理侧查询时过滤。未同步记录的删除只发生在设备本地，不会上行。

连接策略：与 ``location_store`` 同款——**每操作独立连接**
（``contextmanager`` 每次新建、用完即关），线程安全；启动时 MySQL
不可达不拖垮整个服务（首次使用时报 503 由端点转译）。
库/表不存在自动创建（应用启动即建，无需手工初始化）。

线程模型：同步阻塞实现，端点用普通 ``def``（Starlette 自动扔线程池）。
"""

from __future__ import annotations

import logging
from contextlib import contextmanager
from dataclasses import dataclass
from typing import Any, Iterator, Optional

import pymysql

logger = logging.getLogger(__name__)


@dataclass
class OdometerRecord:
    """一条里程台账记录（deleted=True 时为墓碑，仅 record_id/ts 有意义）。"""

    record_id: str
    ts_ms: int = 0          # 设备端录入时刻（epoch 毫秒）
    odo_km: float = 0.0     # 三轮车里程表读数 km
    cycle_ah: float = 0.0   # 录入时保护板循环容量 Ah
    volt: Optional[float] = None  # 录入时总压 V
    deleted: bool = False   # True = 删除墓碑

    def to_row(self) -> tuple:
        return (
            self.record_id, self.ts_ms, self.odo_km, self.cycle_ah,
            self.volt, 1 if self.deleted else 0,
        )


class OdometerStore:
    """MySQL 里程台账存储（库/表自动创建，惰性连接自动重连）。"""

    def __init__(self, host: str, port: int, user: str, password: str, database: str) -> None:
        self._host = host
        self._port = port
        self._user = user
        self._password = password
        self._database = database

    # ------------------------------------------------------------------
    # 连接与建库建表（与 location_store 同款骨架）
    # ------------------------------------------------------------------

    def connect(self) -> None:
        """建库建表（启动时调用一次）。运行期不持有连接，避免并发复用。"""
        conn = self._new_connection()
        try:
            with conn.cursor() as cur:
                cur.execute(self._CREATE_TABLE_SQL)
            conn.commit()
        finally:
            conn.close()
        logger.info("里程台账存储 schema 就绪：%s@%s:%s/%s", self._user, self._host, self._port, self._database)

    def _new_connection(self) -> pymysql.connections.Connection:
        """新建一条独立连接（建库前先不带库名连，再 select_db）。"""
        conn = pymysql.connect(
            host=self._host, port=self._port, user=self._user,
            password=self._password, charset="utf8mb4", connect_timeout=10,
        )
        with conn.cursor() as cur:
            cur.execute(
                f"CREATE DATABASE IF NOT EXISTS `{self._database}` "
                "DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci"
            )
        conn.select_db(self._database)
        return conn

    _CREATE_TABLE_SQL = """
    CREATE TABLE IF NOT EXISTS jk_mileage_records (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        device_id VARCHAR(64) NOT NULL,
        record_id VARCHAR(64) NOT NULL,
        record_ts BIGINT NOT NULL DEFAULT 0,
        odo_km DOUBLE NOT NULL DEFAULT 0,
        cycle_ah DOUBLE NOT NULL DEFAULT 0,
        volt DOUBLE NULL,
        deleted TINYINT NOT NULL DEFAULT 0,
        sync_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
            ON UPDATE CURRENT_TIMESTAMP,
        UNIQUE KEY uk_device_record (device_id, record_id),
        INDEX idx_device_ts (device_id, record_ts)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """

    @contextmanager
    def _connection(self) -> Iterator[pymysql.connections.Connection]:
        """每操作独立连接（线程安全），用完即关，避免并发复用同一 socket。"""
        conn = self._new_connection()
        try:
            yield conn
        finally:
            try:
                conn.close()
            except Exception:
                pass

    def close(self) -> None:
        """兼容旧签名：每操作独立连接，无需持有/关闭。"""

    # ------------------------------------------------------------------
    # 写入（幂等 upsert + 墓碑删除）
    # ------------------------------------------------------------------

    def sync_batch(self, device_id: str, records: list[OdometerRecord]) -> dict[str, Any]:
        """批量同步（设备端一次推送 N 条，含墓碑）。

        返回 ``{"received": 收到条数, "inserted": 新插行数,
        "updated": 覆盖行数}``（inserted/updated 由 changed-row 语义近似：
        upsert 命中已有行时 pymysql 返回 affected=2、新插=1）。
        """
        if not records:
            return {"received": 0, "inserted": 0, "updated": 0}
        inserted = updated = 0
        with self._connection() as conn, conn.cursor() as cur:
            for rec in records:
                affected = cur.execute(
                    "INSERT INTO jk_mileage_records "
                    "(device_id, record_id, record_ts, odo_km, cycle_ah, volt, deleted) "
                    "VALUES (%s, %s, %s, %s, %s, %s, %s) "
                    "ON DUPLICATE KEY UPDATE record_ts=VALUES(record_ts), "
                    "odo_km=VALUES(odo_km), cycle_ah=VALUES(cycle_ah), "
                    "volt=VALUES(volt), deleted=VALUES(deleted)",
                    (device_id, rec.record_id, rec.ts_ms, rec.odo_km,
                     rec.cycle_ah, rec.volt, 1 if rec.deleted else 0),
                )
                # upsert：新插 affected=1，覆盖 affected=2（0 = 数据相同无变化）
                if affected >= 2:
                    updated += 1
                elif affected == 1:
                    inserted += 1
            conn.commit()
        logger.info(
            "里程台账同步：device=%s received=%s inserted=%s updated=%s",
            device_id, len(records), inserted, updated,
        )
        return {"received": len(records), "inserted": inserted, "updated": updated}

    # ------------------------------------------------------------------
    # 查询（管理侧，P2 预留）
    # ------------------------------------------------------------------

    def list(
        self,
        device_id: str,
        limit: int = 500,
    ) -> list[dict[str, Any]]:
        """某设备台账（record_ts 倒序，含墓碑行供管理侧标记删除）。"""
        with self._connection() as conn, conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(
                "SELECT * FROM jk_mileage_records WHERE device_id = %s "
                "ORDER BY record_ts DESC, id DESC LIMIT %s",
                (device_id, limit),
            )
            return [self._row_to_dict(r) for r in cur.fetchall()]

    @staticmethod
    def _row_to_dict(row: dict) -> dict[str, Any]:
        return {
            "id": int(row["id"]),
            "device_id": row["device_id"],
            "record_id": row["record_id"],
            "ts_ms": int(row["record_ts"]),
            "odo_km": float(row["odo_km"]),
            "cycle_ah": float(row["cycle_ah"]),
            "volt": float(row["volt"]) if row["volt"] is not None else None,
            "deleted": bool(row["deleted"]),
            "sync_time": row["sync_time"].strftime("%Y-%m-%d %H:%M:%S"),
        }


def build_odometer_store(mysql_cfg) -> OdometerStore:
    """按配置组装存储实例（app/main.py 装配用）。"""
    return OdometerStore(
        host=mysql_cfg.host,
        port=mysql_cfg.port,
        user=mysql_cfg.user,
        password=mysql_cfg.password,
        database=mysql_cfg.database,
    )
