"""设备 token 持久化存储层（MySQL 实现）。

背景：DeviceRegistry 原为纯内存实现，服务重启会清空全部已注册设备——
客户端本地持久化的旧 token 立即失效，重连时被服务端以 4004（unauthorized）
拒绝，陷入「重连→401」循环。本层把设备 token 落库，重启后依旧有效；
DeviceRegistry 以内存 dict 作一级缓存，DB 为持久层（写穿透 write-through，
读穿透 read-through），与 AdminTokenStore（管理员 token）完全对齐。

容错策略与各 store 一致：MySQL 不可达时降级为纯内存模式
（注册/鉴权照常工作，只是重启后设备需重新注册），绝不拖垮鉴权主链路。

线程模型：同步阻塞实现（pymysql），与各 store 相同；DeviceRegistry 的调用方
（REST 端点 / WS 握手）为普通 ``def`` 或 async 端点，阻塞 IO 由事件循环
run_in_executor 转线程池执行。
"""

from __future__ import annotations

import logging
from contextlib import contextmanager
from datetime import datetime
from typing import Iterator, Optional

import pymysql

logger = logging.getLogger(__name__)


class DeviceTokenStore:
    """MySQL 设备 token 存储（库/表自动创建，每操作独立连接）。"""

    def __init__(self, host: str, port: int, user: str, password: str, database: str) -> None:
        self._host = host
        self._port = port
        self._user = user
        self._password = password
        self._database = database

    # ------------------------------------------------------------------
    # 连接与建库建表
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
        logger.info("设备 token 存储 schema 就绪：%s@%s:%s/%s", self._user, self._host, self._port, self._database)

    _CREATE_TABLE_SQL = """
    CREATE TABLE IF NOT EXISTS device_tokens (
        device_id VARCHAR(64) PRIMARY KEY COMMENT '设备唯一标识（ANDROID_ID）',
        token VARCHAR(64) NOT NULL COMMENT '鉴权 token（secrets.token_hex(32)）',
        device_name VARCHAR(255) NOT NULL DEFAULT '' COMMENT '设备昵称（缺省 device_id）',
        created_at DOUBLE NOT NULL COMMENT '注册时间戳（epoch 秒）',
        last_seen_at DOUBLE NOT NULL COMMENT '最近一次鉴权成功时间戳（epoch 秒）',
        updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
    ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4
    """

    def _new_connection(self) -> pymysql.connections.Connection:
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
        return conn

    @contextmanager
    def _connection(self) -> Iterator[pymysql.connections.Connection]:
        """每操作独立连接（线程安全），用完即关。"""
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
    # 读写
    # ------------------------------------------------------------------

    def upsert(self, device_id: str, token: str, device_name: str,
               created_at: float, last_seen_at: float) -> None:
        """写入/覆盖一条设备（写穿透，由 DeviceRegistry 在注册时调用）。"""
        with self._connection() as conn, conn.cursor() as cur:
            cur.execute(
                "INSERT INTO device_tokens (device_id, token, device_name, created_at, last_seen_at) "
                "VALUES (%s, %s, %s, %s, %s) "
                "ON DUPLICATE KEY UPDATE token = VALUES(token), device_name = VALUES(device_name), "
                "created_at = VALUES(created_at), last_seen_at = VALUES(last_seen_at)",
                (device_id, token, device_name, created_at, last_seen_at),
            )
            conn.commit()

    def get(self, device_id: str) -> Optional[dict]:
        """查询单条设备；不存在返回 None（读穿透）。"""
        with self._connection() as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT token, device_name, created_at, last_seen_at "
                "FROM device_tokens WHERE device_id = %s",
                (device_id,),
            )
            row = cur.fetchone()
        if row is None:
            return None
        return {
            "token": row[0],
            "device_name": row[1],
            "created_at": float(row[2]),
            "last_seen_at": float(row[3]),
        }

    def update_name(self, device_id: str, device_name: str) -> None:
        """更新设备昵称（不刷新 token）。"""
        with self._connection() as conn, conn.cursor() as cur:
            cur.execute(
                "UPDATE device_tokens SET device_name = %s WHERE device_id = %s",
                (device_name, device_id),
            )
            conn.commit()

    def touch_last_seen(self, device_id: str, last_seen_at: float) -> None:
        """刷新最近鉴权成功时间（authenticate 成功时调用，供 token_ttl 判定）。"""
        with self._connection() as conn, conn.cursor() as cur:
            cur.execute(
                "UPDATE device_tokens SET last_seen_at = %s WHERE device_id = %s",
                (last_seen_at, device_id),
            )
            conn.commit()

    def load_all(self) -> dict[str, dict]:
        """启动时全量加载（预热内存缓存）。返回 device_id -> {token, device_name, created_at, last_seen_at}。"""
        with self._connection() as conn, conn.cursor() as cur:
            cur.execute(
                "SELECT device_id, token, device_name, created_at, last_seen_at FROM device_tokens"
            )
            rows = cur.fetchall()
        return {
            row[0]: {
                "token": row[1],
                "device_name": row[2],
                "created_at": float(row[3]),
                "last_seen_at": float(row[4]),
            }
            for row in rows
        }

    def delete(self, device_id: str) -> None:
        """删除一条设备（remove 时调用）。"""
        with self._connection() as conn, conn.cursor() as cur:
            cur.execute("DELETE FROM device_tokens WHERE device_id = %s", (device_id,))
            conn.commit()
