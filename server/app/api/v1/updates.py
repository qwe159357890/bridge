"""应用更新检查 REST 接口（check_update 命令，服务器目录版，不依赖 GitHub）。

约定流程：
- 开发者把最新 APK 放进更新目录（``updates.dir``，默认 ``server/data/updates/``），
  文件名含版本号（如 ``bridge-v0.1.32.apk``），可选放 ``update.json`` 写更新说明：
  ``{"version": "0.1.32", "notes": "新增截屏、弹窗等命令"}``；
- 客户端 ``GET /api/updates/latest?current_version=0.1.31``：服务端扫描目录取
  版本最高的 APK，与客户端当前版本比对，返回是否可用更新；
- APK 下载走服务器自身：``GET /api/updates/{filename}``（不碰 GitHub，通道稳定）。

安全：仅放行扩展名 ``.apk`` 且单级纯文件名（拒绝路径穿越）。
"""

from __future__ import annotations

import json
import logging
import mimetypes
import re
from pathlib import Path

from fastapi import APIRouter, Header, HTTPException, Request, status
from fastapi.responses import FileResponse

from app.api.v1.music import _check_device_auth
from app.config import get_settings

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/updates", tags=["updates"])

# 版本号解析：取文件名里第一段形如 1.2.3 / v0.1.32 的数字串
_VERSION_RE = re.compile(r"v?(\d+(?:\.\d+)+)", re.IGNORECASE)


def _md5_of(path: Path) -> str:
    """现算文件 md5（用户拍板：每次请求现算，永远新鲜；18MB APK 约几十毫秒）。

    用途：客户端缓存命中判定 + 下载完成后的完整性校验，防「文件名相同但
    内容被换过」（如 0.1.43 改名冒充 0.1.44 导致客户端永远无法更新）。
    """
    import hashlib

    h = hashlib.md5()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def _updates_dir() -> Path:
    """解析更新目录（相对路径相对 server 根目录）。"""
    path = Path(get_settings().updates.dir)
    if not path.is_absolute():
        path = Path(__file__).resolve().parent.parent.parent.parent / path
    return path


def _parse_version(text: str) -> tuple[int, ...] | None:
    """从文件名/版本串解析版本号元组（不可解析返回 None）。"""
    m = _VERSION_RE.search(text)
    if not m:
        return None
    try:
        return tuple(int(p) for p in m.group(1).split("."))
    except ValueError:
        return None


def _scan_latest() -> dict | None:
    """扫描更新目录，取版本最高的 APK。返回最新文件信息或 None。"""
    directory = _updates_dir()
    if not directory.is_dir():
        return None

    candidates: list[tuple[tuple[int, ...], Path]] = []
    for f in directory.iterdir():
        if not f.is_file() or f.suffix.lower() != ".apk":
            continue
        version = _parse_version(f.stem)
        if version is None:
            logger.warning("更新目录里的 APK 文件名不含版本号，跳过：%s", f.name)
            continue
        candidates.append((version, f))

    if not candidates:
        return None
    candidates.sort(key=lambda item: item[0])
    version, path = candidates[-1]

    # 可选 update.json：{"version": "...", "notes": "..."}
    notes = ""
    meta_path = directory / "update.json"
    if meta_path.is_file():
        try:
            meta = json.loads(meta_path.read_text(encoding="utf-8"))
            notes = str(meta.get("notes", "")).strip()
        except Exception:
            logger.warning("update.json 解析失败（忽略）", exc_info=True)

    return {
        "version": ".".join(str(p) for p in version),
        "filename": path.name,
        "size_bytes": path.stat().st_size,
        "path": path,
        "notes": notes,
    }


def _validate_filename(filename: str) -> Path:
    """校验文件名：单级纯文件名 + .apk 扩展名。"""
    if (
        not filename
        or "/" in filename
        or "\\" in filename
        or ".." in filename
        or Path(filename).name != filename
    ):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, detail="仅支持单级文件名")
    if Path(filename).suffix.lower() != ".apk":
        raise HTTPException(status.HTTP_400_BAD_REQUEST, detail="仅支持 .apk 文件")
    return _updates_dir() / filename


@router.get("/latest", status_code=status.HTTP_200_OK)
async def latest_update(
    request: Request,
    current_version: str = "",
    x_device_id: str | None = Header(default=None, alias="X-Device-Id"),
    x_device_token: str | None = Header(default=None, alias="X-Device-Token"),
) -> dict:
    """检查更新：目录最新版本 vs 客户端当前版本（比对在服务端完成）。"""
    _check_device_auth(request, x_device_id, x_device_token)
    latest = _scan_latest()
    if latest is None:
        return {"found": False, "available": False, "current_version": current_version}

    current = _parse_version(current_version)
    latest_version = _parse_version(latest["version"])
    # 当前版本不可解析时保守视为「有更新」（客户端 versionName 异常的兜底）
    available = current is None or (latest_version is not None and current < latest_version)
    return {
        "found": True,
        "available": available,
        "current_version": current_version,
        "version": latest["version"],
        "filename": latest["filename"],
        "size_bytes": latest["size_bytes"],
        "md5": _md5_of(latest["path"]),
        "notes": latest["notes"],
    }


@router.get("/{filename}", status_code=status.HTTP_200_OK)
async def download_update(
    filename: str,
    request: Request,
    x_device_id: str | None = Header(default=None, alias="X-Device-Id"),
    x_device_token: str | None = Header(default=None, alias="X-Device-Token"),
) -> FileResponse:
    """下载更新目录里的 APK（客户端/人工下载，服务器自身直链）。"""
    _check_device_auth(request, x_device_id, x_device_token)
    target = _validate_filename(filename)
    if not target.is_file():
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="服务器更新目录无此文件")
    logger.info("下发更新 APK %s（设备 %s）", filename, x_device_id or "anonymous")
    return FileResponse(
        path=target,
        filename=filename,
        media_type="application/vnd.android.package-archive",
    )
