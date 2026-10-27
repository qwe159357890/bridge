"""媒体文件存储层（screenshot / take_photo / record_screen 上传存档）。

存储分两层：
- **元数据**：MySQL ``media_files`` 表（设备、类型、文件名、大小、时间）；
- **文件本体**：服务器磁盘 ``media.dir``（默认 ``data/media/``），按设备子目录存放。

清理策略（上传时触发）：保留**最近 ``max_count`` 条**且**不超过 ``max_age_days`` 天**，
任一条件先到先触发；被清理的记录连同磁盘文件一起删除（对齐位置数据的
「无其他自动清理」原则之外的显式约定：媒体文件按数量/时间自动清理，2026-10-26）。
"""

from __future__ import annotations

import logging
import shutil
from datetime import datetime, timedelta
from pathlib import Path
from typing import Any, Optional

import pymysql

logger = logging.getLogger(__name__)

# 允许入库的扩展名白名单（与客户端上传类型对应）
# - 常规采集：jpg/png（图片）、mp4（录像/录屏）、m4a（录音）
# - 流式采集产物（2026-10-27 补）：flv（投屏直播 FLV）、ts（流式录像 MPEG-2 TS）、
#   aac（流式录音 AAC ADTS）——此前缺失导致流式结束后上传 500「不支持的媒体格式」
MEDIA_EXTENSIONS = {".jpg", ".jpeg", ".png", ".mp4", ".m4a", ".flv", ".ts", ".aac"}


class MediaStore:
    """MySQL 元数据 + 磁盘文件的媒体存储。"""

    def __init__(
        self,
        host: str,
        port: int,
        user: str,
        password: str,
        database: str,
        base_dir: Path,
        max_count: int = 100,
        max_age_days: int = 7,
    ) -> None:
        self._host = host
        self._port = port
        self._user = user
        self._password = password
        self._database = database
        self._base_dir = base_dir
        self._max_count = max_count
        self._max_age_days = max_age_days
        self._conn: Optional[pymysql.connections.Connection] = None

    _CREATE_TABLE_SQL = """
    CREATE TABLE IF NOT EXISTS media_files (
        id BIGINT AUTO_INCREMENT PRIMARY KEY,
        device_id VARCHAR(64) NOT NULL,
        kind VARCHAR(16) NOT NULL,
        filename VARCHAR(255) NOT NULL,
        size BIGINT NOT NULL DEFAULT 0,
        created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
        KEY idx_device (device_id),
        KEY idx_created (created_at)
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
                logger.warning("媒体存储连接失效，重连", exc_info=True)
                self._close_quietly()

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
        logger.info("媒体存储已连接：%s@%s:%s/%s（目录 %s）", self._user, self._host, self._port, self._database, self._base_dir)

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
    # 写入
    # ------------------------------------------------------------------

    def save(self, device_id: str, kind: str, upload_name: str, src_file: Path) -> dict[str, Any]:
        """保存一个上传文件：移入设备子目录 + 落库 + 触发清理。

        ``upload_name`` 仅用于取扩展名（防路径穿越）；落盘文件名由服务端生成。
        """
        ext = Path(upload_name).suffix.lower()
        if ext not in MEDIA_EXTENSIONS:
            raise ValueError(f"不支持的媒体格式：{ext or '无后缀'}")

        device_dir = self._base_dir / device_id
        device_dir.mkdir(parents=True, exist_ok=True)
        stamp = datetime.now().strftime("%Y%m%d_%H%M%S_%f")
        filename = f"{stamp}_{kind}{ext}"
        target = device_dir / filename
        shutil.move(str(src_file), str(target))
        size = target.stat().st_size

        conn = self._connection()
        with conn.cursor() as cur:
            cur.execute(
                "INSERT INTO media_files (device_id, kind, filename, size) VALUES (%s, %s, %s, %s)",
                (device_id, kind, filename, size),
            )
            record_id = cur.lastrowid
            conn.commit()

        cleaned = self._cleanup()
        logger.info(
            "媒体文件入库：device=%s kind=%s file=%s（%d 字节，清理 %d 条旧记录）",
            device_id, kind, filename, size, cleaned,
        )
        return {
            "id": int(record_id),
            "filename": filename,
            "size": size,
        }

    # ------------------------------------------------------------------
    # 查询 / 删除（管理侧）
    # ------------------------------------------------------------------

    def list(self, limit: int = 200) -> list[dict[str, Any]]:
        """按时间倒序列出媒体记录（新在前）。"""
        conn = self._connection()
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute(
                "SELECT * FROM media_files ORDER BY created_at DESC, id DESC LIMIT %s",
                (int(limit),),
            )
            rows = cur.fetchall()
        return [self._row_to_dict(r) for r in rows]

    def get(self, record_id: int) -> Optional[dict[str, Any]]:
        """按 ID 取一条记录。"""
        conn = self._connection()
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            cur.execute("SELECT * FROM media_files WHERE id = %s", (record_id,))
            row = cur.fetchone()
        return self._row_to_dict(row) if row else None

    def file_path_of(self, record: dict[str, Any]) -> Path:
        """记录对应磁盘文件路径。"""
        return self._base_dir / record["device_id"] / record["filename"]

    def delete(self, record_id: int) -> bool:
        """删除一条记录连同磁盘文件，返回是否删除成功。"""
        record = self.get(record_id)
        if record is None:
            return False
        conn = self._connection()
        with conn.cursor() as cur:
            cur.execute("DELETE FROM media_files WHERE id = %s", (record_id,))
            conn.commit()
        path = self.file_path_of(record)
        try:
            if path.is_file():
                path.unlink()
        except Exception:
            logger.warning("删除媒体文件失败（记录已删）：%s", path, exc_info=True)
        self._remove_thumb(record)
        logger.info("媒体文件已删除：id=%s file=%s", record_id, record["filename"])
        return True

    # ------------------------------------------------------------------
    # 自动清理
    # ------------------------------------------------------------------

    def _cleanup(self) -> int:
        """按数量/时间清理旧记录（保留最新 max_count 条且不过 max_age_days 天）。"""
        removed = 0
        conn = self._connection()
        with conn.cursor(pymysql.cursors.DictCursor) as cur:
            # 时间触发：超过 max_age_days 的全部删除
            if self._max_age_days > 0:
                deadline = datetime.now() - timedelta(days=self._max_age_days)
                cur.execute(
                    "SELECT id, device_id, filename FROM media_files WHERE created_at < %s",
                    (deadline,),
                )
                stale = cur.fetchall()
                if stale:
                    cur.executemany(
                        "DELETE FROM media_files WHERE id = %s",
                        [(r["id"],) for r in stale],
                    )
                    removed += len(stale)
                    self._remove_files(stale)

            # 数量触发：保留最新 max_count 条，其余删除
            if self._max_count > 0:
                cur.execute(
                    "SELECT id, device_id, filename FROM media_files ORDER BY created_at DESC, id DESC "
                    "LIMIT %s, 18446744073709551615",
                    (self._max_count,),
                )
                overflow = cur.fetchall()
                if overflow:
                    cur.executemany(
                        "DELETE FROM media_files WHERE id = %s",
                        [(r["id"],) for r in overflow],
                    )
                    removed += len(overflow)
                    self._remove_files(overflow)

        if removed:
            conn.commit()
            logger.info("媒体自动清理：%d 条（max_count=%d, max_age_days=%d）", removed, self._max_count, self._max_age_days)
        return removed

    def _remove_files(self, rows: list[dict[str, Any]]) -> None:
        for r in rows:
            path = self._base_dir / r["device_id"] / r["filename"]
            try:
                if path.is_file():
                    path.unlink()
            except Exception:
                logger.warning("清理媒体文件失败（忽略）：%s", path, exc_info=True)
            self._remove_thumb(r)

    def _remove_thumb(self, row: dict[str, Any]) -> None:
        """同步清理缩略图缓存（thumbs/{id}{后缀}.thumb.jpg；后缀未知则按通配匹配）。"""
        thumb_dir = self._base_dir / "thumbs"
        suffix = Path(row["filename"]).suffix.lower()
        if suffix:
            candidates = [thumb_dir / f"{row['id']}{suffix}.thumb.jpg"]
        else:
            candidates = list(thumb_dir.glob(f"{row['id']}.*.thumb.jpg"))
        for t in candidates:
            try:
                if t.is_file():
                    t.unlink()
            except Exception:
                logger.warning("清理缩略图失败（忽略）：%s", t, exc_info=True)

    @staticmethod
    def _row_to_dict(row: dict) -> dict[str, Any]:
        return {
            "id": int(row["id"]),
            "device_id": row["device_id"],
            "kind": row["kind"],
            "filename": row["filename"],
            "size": int(row["size"]),
            "created_at": row["created_at"].strftime("%Y-%m-%d %H:%M:%S"),
        }


def build_media_store(mysql_cfg, base_dir: Path, max_count: int, max_age_days: int) -> MediaStore:
    """按配置组装存储实例（app/main.py 装配用）。"""
    return MediaStore(
        host=mysql_cfg.host,
        port=mysql_cfg.port,
        user=mysql_cfg.user,
        password=mysql_cfg.password,
        database=mysql_cfg.database,
        base_dir=base_dir,
        max_count=max_count,
        max_age_days=max_age_days,
    )
