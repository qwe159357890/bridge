"""音乐共享下载 REST 接口（play_music 命令的「裸文件名」音源机制）。

约定流程：
- 管理员把音乐文件放进共享目录（``music.dir``，默认 ``server/data/music/``）；
- 客户端收到 ``play_music(url="test.mp3")`` 时先查本地缓存目录，未命中则请求
  ``GET /api/music/test.mp3`` 下载并持久化到缓存目录，随后播放；
- 下次下发同名文件名直接命中本地缓存，不再请求服务端。

鉴权：``auth.enabled=true`` 时要求设备头（``X-Device-Id`` + ``X-Device-Token``），
与 WS hello 握手共用同一套 ``registry.authenticate``；关闭鉴权时放行（便于联调）。

安全：仅允许**单级纯文件名**（拒绝 ``/``、``\\``、``..`` 等路径穿越），
且扩展名须在音乐白名单内（防止共享目录里混入其他文件被任意读取）。
"""

from __future__ import annotations

import logging
import mimetypes
from pathlib import Path

from fastapi import APIRouter, Header, HTTPException, Request, status
from fastapi.responses import FileResponse

from app.config import get_settings

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/music", tags=["music"])

# 音乐扩展名白名单：目录里就算混入别的文件也不外发
MUSIC_EXTENSIONS = {".mp3", ".m4a", ".aac", ".wav", ".ogg", ".flac", ".opus", ".mid", ".amr"}


def _music_dir() -> Path:
    """解析音乐共享目录（相对路径相对 server 根目录，即 app/ 的上级）。"""
    path = Path(get_settings().music.dir)
    if not path.is_absolute():
        # app/api/v1/music.py -> app/api/v1 -> app/api -> app -> server 根
        path = Path(__file__).resolve().parent.parent.parent.parent / path
    return path


def _validate_filename(filename: str) -> Path:
    """校验文件名：仅允许单级纯文件名 + 音乐扩展名，返回目录内目标路径。"""
    if (
        not filename
        or "/" in filename
        or "\\" in filename
        or ".." in filename
        or Path(filename).name != filename
    ):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, detail="仅支持单级文件名")
    ext = Path(filename).suffix.lower()
    if ext not in MUSIC_EXTENSIONS:
        raise HTTPException(
            status.HTTP_400_BAD_REQUEST,
            detail=f"不支持的音乐格式：{ext or '无后缀'}",
        )
    return _music_dir() / filename


def _check_device_auth(request: Request, device_id: str | None, token: str | None) -> None:
    """设备鉴权（auth.enabled 时生效，与 WS hello 同一套注册表校验）。"""
    settings = request.app.state.settings
    if not settings.auth.enabled:
        return
    registry = request.app.state.device_registry
    if not device_id or not token or not registry.authenticate(device_id, token):
        logger.warning("音乐下载鉴权失败：device=%s", device_id or "unknown")
        raise HTTPException(status.HTTP_401_UNAUTHORIZED, detail="设备鉴权失败")


@router.get("/{filename}")
async def download_music(
    filename: str,
    request: Request,
    x_device_id: str | None = Header(default=None, alias="X-Device-Id"),
    x_device_token: str | None = Header(default=None, alias="X-Device-Token"),
) -> FileResponse:
    """下载共享目录里的音乐文件（客户端缓存未命中时调用，404 表示无此文件）。"""
    _check_device_auth(request, x_device_id, x_device_token)
    target = _validate_filename(filename)
    if not target.is_file():
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="服务器音乐目录无此文件")
    media_type = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
    logger.info("下发音乐文件 %s（设备 %s）", target.name, x_device_id or "anonymous")
    return FileResponse(path=target, filename=target.name, media_type=media_type)
