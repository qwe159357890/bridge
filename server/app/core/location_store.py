"""位置持久化存储层（get_location 上报存档，MySQL 实现）。

存储语义与 music_state（状态覆盖写）相反：位置是**事件**，append-only
历史；唯一例外是「静止合并」——设备未移动时的新上报不建新行，
只刷新最新一行的坐标/时间/计数（见 ``report``）。

判重规则：与该设备**最新一行**算 haversine 球面距离，
``距离 < max(两次定位精度, 30米)`` 视为两个误差圈重叠（无法确认移动）
→ 合并；否则插入新行。真实移动的距离必然超出误差圈，不会被误合并。

坐标系：客户端上报为 WGS-84 原始坐标；入库时同步计算 GCJ-02（国测局/
火星坐标，高德腾讯底图用），双坐标都存——画图用 GCJ-02，导出/原始用
WGS-84。转换算法仅中国境内生效，境外自动原样返回。

连接策略：惰性连接 + 自动重连（``conn.ping(reconnect=True)``），
启动时 MySQL 不可达不拖垮整个服务（首次使用时报 503 由端点转译）。
库/表不存在自动创建（应用启动即建，无需手工初始化）。

线程模型：同步阻塞实现，端点用普通 ``def``（Starlette 自动扔线程池）。
"""

from __future__ import annotations

import logging
import math
from dataclasses import dataclass
from datetime import datetime
from typing import Any, Optional

import pymysql

logger = logging.getLogger(__name__)

# 静止合并的精度下限（米）：两次精度都极小时（如 GPS ±5m）也不至于
# 因正常漂移反复建行
MERGE_MIN_METERS = 30.0

# WGS-84 -> GCJ-02 转换常量（克拉索夫斯基椭球）
_GCJ_A = 6378245.0
_GCJ_EE = 0.00669342162296594323


def haversine_m(lat1: float, lon1: float, lat2: float, lon2: float) -> float:
    """两点球面距离（米），haversine 公式，地球半径取 6371008.8 米。"""
    r = 6371008.8
    p1 = math.radians(lat1)
    p2 = math.radians(lat2)
    dp = math.radians(lat2 - lat1)
    dl = math.radians(lon2 - lon1)
    a = math.sin(dp / 2) ** 2 + math.cos(p1) * math.cos(p2) * math.sin(dl / 2) ** 2
    return 2 * r * math.asin(math.sqrt(a))


def _out_of_china(lat: float, lon: float) -> bool:
    """是否在中国境外（境外无 GCJ-02 偏移，原样返回）。"""
    return not (73.66 < lon < 135.05 and 3.86 < lat < 53.55)


def _transform_lat(x: float, y: float) -> float:
    ret = (
        -100.0 + 2.0 * x + 3.0 * y + 0.2 * y * y + 0.1 * x * y
        + 0.2 * math.sqrt(abs(x))
    )
    ret += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    ret += (20.0 * math.sin(y * math.pi) + 40.0 * math.sin(y / 3.0 * math.pi)) * 2.0 / 3.0
    ret += (160.0 * math.sin(y / 12.0 * math.pi) + 320 * math.sin(y * math.pi / 30.0)) * 2.0 / 3.0
    return ret


def _transform_lon(x: float, y: float) -> float:
    ret = (
        300.0 + x + 2.0 * y + 0.1 * x * x + 0.1 * x * y
        + 0.1 * math.sqrt(abs(x))
    )
    ret += (20.0 * math.sin(6.0 * x * math.pi) + 20.0 * math.sin(2.0 * x * math.pi)) * 2.0 / 3.0
    ret += (20.0 * math.sin(x * math.pi) + 40.0 * math.sin(x / 3.0 * math.pi)) * 2.0 / 3.0
    ret += (150.0 * math.sin(x / 12.0 * math.pi) + 300.0 * math.sin(x / 30.0 * math.pi)) * 2.0 / 3.0
    return ret


def wgs84_to_gcj02(lat: float, lon: float) -> tuple[float, float]:
    """WGS-84 转 GCJ-02（火星坐标）；境外原样返回。"""
    if _out_of_china(lat, lon):
        return lat, lon
    dlat = _transform_lat(lon - 105.0, lat - 35.0)
    dlon = _transform_lon(lon - 105.0, lat - 35.0)
    radlat = lat / 180.0 * math.pi
    magic = 1 - _GCJ_EE * math.sin(radlat) ** 2
    sqrtmagic = math.sqrt(magic)
    dlat = (dlat * 180.0) / ((_GCJ_A * (1 - _GCJ_EE)) / (magic * sqrtmagic) * math.pi)
    dlon = (dlon * 180.0) / (_GCJ_A / sqrtmagic * math.cos(radlat) * math.pi)
    return lat + dlat, lon + dlon


@dataclass
class LocationRecord:
    """一条位置记录（静止合并后代表「同一点的最新估计」）。"""

    device_id: str
    latitude: float
    longitude: float
    accuracy: float = 0.0
    provider: str = ""
    altitude: Optional[float] = None
    speed: Optional[float] = None
    fix_time_ms: int = 0  # 设备端定位时刻（epoch 毫秒）

    def to_dict(self) -> dict[str, Any]:
        return {
            "device_id": self.device_id,
            "latitude": self.latitude,
            "longitude": self.longitude,
            "accuracy": self.accuracy,
            "provider": self.provider,
            "altitude": self.altitude,
            "speed": self.speed,
            "fix_time_ms": self.fix_time_ms,
        }


class LocationStore:
    """MySQL 位置存储（库/表自动创建，惰性连接自动重连）。"""

    def __init__(self, host: str, port: int, user: str, password: str, database: str) -> None:
        self._host = host
        self._port = port
        self._user = user
        self._password = password
        self._database = database
        self._conn: Optional[pymysql.connections.Connection] = None

    # ------------------------------------------------------------------
    # 连接与建库建表
    # ------------------------------------------------------------------

    def connect(self) -> None:
        """建立连接；库/表不存在自动创建。可重复调用（已有连接则跳过）。"""
        if self._conn is not None:
            try:
                self._conn.ping(reconnect=True)
                return
            except Exception:
                logger.warning("位置存储连接失效，重连", exc_info=True)
                self._close_quietly()

        # 先不带库名连接，建库后重连（库可能尚未创建）
        conn = pymysql.connect(
            host=self._host, port=self._port, user=self._user,
            password=self._password, database=self._database, charset="utf8mb4",
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
        logger.info("位置存储已连接：%s@%s:%s/%s", self._user, self._host, self._port, self._database)

    _CREATE_TABLE_SQL = """
    CREATE TABLE IF NOT EXISTS locations (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        device_id VARCHAR(64) NOT NULL,
        latitude DOUBLE NOT NULL,
        longitude DOUBLE NOT NULL,
        accuracy DOUBLE NOT NULL DEFAULT 0,
        provider VARCHAR(64) NOT NULL DEFAULT '',
        altitude DOUBLE NULL,
        speed DOUBLE NULL,
        gcj_latitude DOUBLE NULL,
        gcj_longitude DOUBLE NULL,
        first_fix_time DATETIME NOT NULL,
        last_fix_time DATETIME NOT NULL,
        server_time DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
            ON UPDATE CURRENT_TIMESTAMP,
        report_count INT NOT NULL DEFAULT 1,
        INDEX idx_device_lastfix (device_id, last_fix_time)
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """

    def _connection(self) -> pymysql.connections.Connection:
        """取可用连接（惰性建立；断线自动重连）。"""
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
        """应用关闭时释放连接。"""
        self._close_quietly()

    # ------------------------------------------------------------------
    # 写入（含静止合并）
    # ------------------------------------------------------------------

    def report(self, record: LocationRecord) -> dict[str, Any]:
        """接收一次位置上报：静止合并或新建行。

        返回 ``{"action": "merged"|"inserted", "id": 行id,
        "distance_m": 与上一条的距离(合并时为合并判定值，新建时为实际距离,
        "report_count": 该行累计上报次数)}``。
        """
        conn = self._connection()
        fix_dt = datetime.fromtimestamp(record.fix_time_ms / 1000.0) if record.fix_time_ms else datetime.now()
        gcj_lat, gcj_lon = wgs84_to_gcj02(record.latitude, record.longitude)

        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(
                "SELECT id, latitude, longitude, accuracy, report_count "
                "FROM locations WHERE device_id = %s "
                "ORDER BY last_fix_time DESC, id DESC LIMIT 1",
                (record.device_id,),
            )
            last = cur.fetchone()

            if last is not None:
                dist = haversine_m(
                    float(last["latitude"]), float(last["longitude"]),
                    record.latitude, record.longitude,
                )
                threshold = max(
                    float(last["accuracy"] or 0.0), record.accuracy, MERGE_MIN_METERS
                )
                if dist < threshold:
                    cur.execute(
                        "UPDATE locations SET latitude=%s, longitude=%s, "
                        "gcj_latitude=%s, gcj_longitude=%s, accuracy=%s, "
                        "provider=%s, altitude=%s, speed=%s, last_fix_time=%s, "
                        "report_count=report_count+1 WHERE id=%s",
                        (
                            record.latitude, record.longitude, gcj_lat, gcj_lon,
                            record.accuracy, record.provider, record.altitude,
                            record.speed, fix_dt, last["id"],
                        ),
                    )
                    conn.commit()
                    logger.info(
                        "位置合并上报：device=%s 距上条 %.0fm < 阈值 %.0fm，行 id=%s",
                        record.device_id, dist, threshold, last["id"],
                    )
                    return {
                        "action": "merged",
                        "id": last["id"],
                        "distance_m": round(dist, 1),
                        "report_count": int(last["report_count"]) + 1,
                    }

            cur.execute(
                "INSERT INTO locations (device_id, latitude, longitude, accuracy, "
                "provider, altitude, speed, gcj_latitude, gcj_longitude, "
                "first_fix_time, last_fix_time) "
                "VALUES (%s, %s, %s, %s, %s, %s, %s, %s, %s, %s, %s)",
                (
                    record.device_id, record.latitude, record.longitude,
                    record.accuracy, record.provider, record.altitude,
                    record.speed, gcj_lat, gcj_lon, fix_dt, fix_dt,
                ),
            )
            new_id = cur.lastrowid
            conn.commit()
            logger.info(
                "位置新建记录：device=%s id=%s (%.6f, %.6f)",
                record.device_id, new_id, record.latitude, record.longitude,
            )
            return {
                "action": "inserted",
                "id": new_id,
                "distance_m": None if last is None else round(dist, 1),
                "report_count": 1,
            }

    # ------------------------------------------------------------------
    # 查询 / 删除（管理侧）
    # ------------------------------------------------------------------

    def list(
        self,
        device_id: Optional[str] = None,
        start_ms: Optional[int] = None,
        end_ms: Optional[int] = None,
        limit: int = 200,
        offset: int = 0,
    ) -> tuple[list[dict[str, Any]], int]:
        """按条件查询（last_fix_time 倒序），返回 (rows, total)。"""
        conn = self._connection()
        where, args = self._where(device_id, start_ms, end_ms)
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(f"SELECT COUNT(*) AS n FROM locations {where}", args)
            total = int(cur.fetchone()["n"])
            cur.execute(
                f"SELECT * FROM locations {where} "
                f"ORDER BY last_fix_time DESC, id DESC LIMIT %s OFFSET %s",
                (*args, limit, offset),
            )
            return [self._row_to_dict(r) for r in cur.fetchall()], total

    def latest(self, device_id: str) -> Optional[dict[str, Any]]:
        """某设备最新一条（无记录返回 None）。"""
        rows, _ = self.list(device_id=device_id, limit=1)
        return rows[0] if rows else None

    def delete(
        self,
        row_id: Optional[int] = None,
        device_id: Optional[str] = None,
        before_ms: Optional[int] = None,
    ) -> int:
        """手动清理（至少一个条件，防误删全表）：返回删除行数。"""
        conn = self._connection()
        conds, args = [], []
        if row_id is not None:
            conds.append("id = %s")
            args.append(row_id)
        if device_id:
            conds.append("device_id = %s")
            args.append(device_id)
        if before_ms is not None:
            conds.append("last_fix_time < %s")
            args.append(datetime.fromtimestamp(before_ms / 1000.0))
        if not conds:
            raise ValueError("delete 需要至少一个条件（id / device_id / before）")
        with conn.cursor() as cur:
            n = cur.execute(f"DELETE FROM locations WHERE {' AND '.join(conds)}", args)
            conn.commit()
            return int(n)

    # ------------------------------------------------------------------
    # 内部
    # ------------------------------------------------------------------

    @staticmethod
    def _where(
        device_id: Optional[str],
        start_ms: Optional[int],
        end_ms: Optional[int],
    ) -> tuple[str, list]:
        conds, args = [], []
        if device_id:
            conds.append("device_id = %s")
            args.append(device_id)
        if start_ms is not None:
            conds.append("last_fix_time >= %s")
            args.append(datetime.fromtimestamp(start_ms / 1000.0))
        if end_ms is not None:
            conds.append("last_fix_time < %s")
            args.append(datetime.fromtimestamp(end_ms / 1000.0))
        return ("WHERE " + " AND ".join(conds)) if conds else "", args

    @staticmethod
    def _row_to_dict(row: dict) -> dict[str, Any]:
        return {
            "id": row["id"],
            "device_id": row["device_id"],
            "latitude": float(row["latitude"]),
            "longitude": float(row["longitude"]),
            "gcj_latitude": float(row["gcj_latitude"]) if row["gcj_latitude"] is not None else None,
            "gcj_longitude": float(row["gcj_longitude"]) if row["gcj_longitude"] is not None else None,
            "accuracy": float(row["accuracy"] or 0.0),
            "provider": row["provider"],
            "altitude": float(row["altitude"]) if row["altitude"] is not None else None,
            "speed": float(row["speed"]) if row["speed"] is not None else None,
            "first_fix_time": row["first_fix_time"].strftime("%Y-%m-%d %H:%M:%S"),
            "last_fix_time": row["last_fix_time"].strftime("%Y-%m-%d %H:%M:%S"),
            "server_time": row["server_time"].strftime("%Y-%m-%d %H:%M:%S"),
            "report_count": int(row["report_count"]),
        }


def build_location_store(mysql_cfg) -> LocationStore:
    """按配置组装存储实例（app/main.py 装配用）。"""
    return LocationStore(
        host=mysql_cfg.host,
        port=mysql_cfg.port,
        user=mysql_cfg.user,
        password=mysql_cfg.password,
        database=mysql_cfg.database,
    )
