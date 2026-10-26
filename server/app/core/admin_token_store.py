"""管理员 token 持久化存储层（MySQL 实现）。

背景：AdminAuth 原为纯内存实现，服务重启会清空全部已签发 token——
浏览器里尚未过期的旧 token 立即失效，管理后台 WS 反复以 403 重连刷日志。
本层把 token 落库，重启后依旧有效；AdminAuth 以内存 dict 作一级缓存，
DB 为持久层（写穿透 write-through，读穿透 read-through）。

容错策略与 location_store 等一致：MySQL 不可达时降级为纯内存模式
（登录/校验照常工作，只是重启后 token 失效），绝不拖垮鉴权主链路。

线程模型：同步阻塞实现（pymysql），与各 store 相同；AdminAuth 的调用方
（REST 端点）为普通 ``def``，Starlette 自动扔线程池。
"""

from __future__ import annotations

import logging
from datetime import datetime
from typing import Optional

import pymysql

logger = logging.getLogger(__name__)


class AdminTokenStore:
    """MySQL 管理员 token 存储（库/表自动创建，惰性连接自动重连）。"""

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
                logger.warning("管理员 token 存储连接失效，重连", exc_info=True)
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
        logger.info("管理员 token 存储已连接：%s@%s:%s/%s", self._user, self._host, self._port, self._database)

    _CREATE_TABLE_SQL = """
    CREATE TABLE IF NOT EXISTS admin_tokens (
        token VARCHAR(64) PRIMARY KEY,
        issued_at DOUBLE NOT NULL COMMENT '签发时间戳（epoch 秒）',
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
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
    # 读写
    # ------------------------------------------------------------------

    def save(self, token: str, issued_at: float) -> None:
        """写入/覆盖一条 token（写穿透，由 AdminAuth 在签发时调用）。"""
        conn = self._connection()
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO admin_tokens (token, issued_at, created_at) VALUES (%s, %s, %s) "
                "ON DUPLICATE KEY UPDATE issued_at = VALUES(issued_at)",
                (token, issued_at, datetime.fromtimestamp(issued_at)),
            )
        conn.commit()

    def get(self, token: str) -> Optional[float]:
        """查询单条 token 的签发时间；不存在返回 None（读穿透）。"""
        conn = self._connection()
        with conn.cursor() as cur:
            cur.execute("SELECT issued_at FROM admin_tokens WHERE token = %s", (token,))
            row = cur.fetchone()
        return float(row[0]) if row else None

    def load_all(self) -> dict[str, float]:
        """启动时全量加载（预热内存缓存），顺便清掉已过期行由调用方过滤。"""
        conn = self._connection()
        with conn.cursor() as cur:
            cur.execute("SELECT token, issued_at FROM admin_tokens")
            rows = cur.fetchall()
        return {row[0]: float(row[1]) for row in rows}

    def delete(self, token: str) -> None:
        """删除一条 token（注销/TTL 过期清理）。"""
        conn = self._connection()
        with conn.cursor() as cur:
            cur.execute("DELETE FROM admin_tokens WHERE token = %s", (token,))
        conn.commit()
