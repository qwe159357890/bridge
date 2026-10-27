"""媒体文件 REST 接口（screenshot / take_photo / record_screen / record_video / record_audio 产物）。

- ``POST /api/media`` 客户端 multipart 上传（设备鉴权），入库 + 触发自动清理；
- ``GET /api/media`` 管理侧列表（倒序，含设备/类型/大小/时间）；
- ``GET /api/media/{id}/file`` 管理侧取文件本体（图片预览 / 视频播放）；
- ``DELETE /api/media/{id}`` 管理侧删除（记录 + 磁盘文件）。
"""

from __future__ import annotations

import logging
import os
import tempfile
from pathlib import Path

from fastapi import APIRouter, Depends, File, Form, Header, HTTPException, Request, UploadFile, status
from fastapi.responses import FileResponse

from app.api.v1.admin import require_admin
from app.api.v1.music import _check_device_auth

try:
    from PIL import Image

    _HAS_PIL = True
except ImportError:  # Pillow 未安装时缩略图端点降级返回原图
    _HAS_PIL = False

logger = logging.getLogger(__name__)

router = APIRouter(prefix="/media", tags=["media"])

# 缩略图最长边（列表预览足够，流量降 90%+）
THUMB_MAX_EDGE = 480

# 客户端上报的 kind 白名单
MEDIA_KINDS = {"screenshot", "photo", "screen_record", "screen_live", "video", "audio"}

# 单文件上限（录屏 60 秒约 40 MB 以内，留余量）
MAX_MEDIA_BYTES = 200 * 1024 * 1024

KIND_LABELS = {
    "screenshot": "截屏",
    "photo": "拍照",
    "screen_record": "录屏",
    "screen_live": "直播存档",
    "video": "录像",
    "audio": "录音",
}


def _get_store(request: Request):
    store = request.app.state.media_store
    if store is None:
        raise HTTPException(status.HTTP_503_SERVICE_UNAVAILABLE, detail="媒体存储不可用（MySQL 未连接）")
    return store


def _resolve_dir(settings) -> Path:
    """解析媒体目录（相对路径相对 server 根目录）。"""
    path = Path(settings.media.dir)
    if not path.is_absolute():
        path = Path(__file__).resolve().parent.parent.parent.parent / path
    return path


@router.post("", status_code=status.HTTP_200_OK)
async def upload_media(
    request: Request,
    kind: str = Form(...),
    file: UploadFile = File(...),
    x_device_id: str | None = Header(default=None, alias="X-Device-Id"),
    x_device_token: str | None = Header(default=None, alias="X-Device-Token"),
) -> dict:
    """客户端上传一个媒体文件（截屏/拍照/录屏产物）。

    kind 必须是 **multipart 表单文本字段**（screenshot/photo/screen_record），
    与客户端 OkHttp ``@Part("kind")`` 对齐——v0.1.37 修复：此前误声明为查询参数，
    客户端把 kind 放在 multipart 体里导致 FastAPI 422（拍照/截屏/录屏上传全挂）。
    file 为 multipart 文件分片。服务端重命名落盘（防路径穿越），返回文件名与大小。
    """
    if kind not in MEDIA_KINDS:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, detail=f"kind 只支持 {'/'.join(sorted(MEDIA_KINDS))}")
    _check_device_auth(request, x_device_id, x_device_token)
    device_id = x_device_id or "anonymous"

    store = _get_store(request)

    # 先落系统临时文件（限流：超上限直接拒绝）
    fd, tmp_path = tempfile.mkstemp(prefix="bridge_media_")
    os.close(fd)
    tmp = Path(tmp_path)

    size = 0
    try:
        with tmp.open("wb") as out:
            while chunk := await file.read(1024 * 1024):
                size += len(chunk)
                if size > MAX_MEDIA_BYTES:
                    raise HTTPException(
                        status.HTTP_413_REQUEST_ENTITY_TOO_LARGE,
                        detail=f"文件超过上限（{MAX_MEDIA_BYTES // 1048576} MB）",
                    )
                out.write(chunk)
        if size == 0:
            raise HTTPException(status.HTTP_400_BAD_REQUEST, detail="文件为空")
        record = store.save(device_id, kind, file.filename or "media.bin", tmp)
    finally:
        try:
            if tmp.exists():
                tmp.unlink()
        except Exception:
            pass

    return {
        "code": 0,
        "message": f"已上传{KIND_LABELS.get(kind, kind)}文件",
        "id": record["id"],
        "filename": record["filename"],
        "size": record["size"],
    }


@router.get("", status_code=status.HTTP_200_OK, dependencies=[Depends(require_admin)])
async def list_media(request: Request, limit: int = 200) -> dict:
    """媒体文件列表（倒序，含类型标签）。"""
    limit = max(1, min(limit, 500))
    store = _get_store(request)
    items = store.list(limit)
    for item in items:
        item["kind_label"] = KIND_LABELS.get(item["kind"], item["kind"])
        item["exists"] = store.file_path_of(item).is_file()
    return {"items": items, "total": len(items)}


@router.get("/{record_id}/file", status_code=status.HTTP_200_OK)
async def download_media(
    record_id: int,
    request: Request,
    token: str | None = None,
) -> FileResponse:
    """取媒体文件本体（管理后台图片预览 / 视频播放 / 下载）。

    鉴权双通道：标准 Bearer 头（axios 下载用）或 ``?token=`` 查询参数
    （``<img>/<video>`` 标签带不了请求头，走 query）。
    """
    _authorize_media(request, token)

    store = _get_store(request)
    record = store.get(record_id)
    if record is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="媒体文件不存在")
    path = store.file_path_of(record)
    if not path.is_file():
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="磁盘文件已不存在（可能被自动清理）")
    return FileResponse(path=path, filename=record["filename"], media_type=_media_type_of(record["filename"]))


@router.get("/{record_id}/thumb", status_code=status.HTTP_200_OK)
async def media_thumb(
    record_id: int,
    request: Request,
    token: str | None = None,
) -> FileResponse:
    """取图片缩略图（管理后台列表预览，替代原图全量下载）。

    - 仅图片类（screenshot/photo）生成缩略图；其他类型 404（前端不会请求）；
    - 首次访问生成并缓存到媒体目录 ``thumbs/`` 子目录（删原图时同步清理）；
    - Pillow 未安装或图片解码失败 → 降级返回原图（列表仍可用，只是不省流量）；
    - 鉴权方式与 ``/file`` 相同：Bearer 头或 ``?token=``。
    """
    _authorize_media(request, token)
    store = _get_store(request)
    record = store.get(record_id)
    if record is None:
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="媒体文件不存在")
    path = store.file_path_of(record)
    if not path.is_file():
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="磁盘文件已不存在（可能被自动清理）")
    if record["kind"] not in ("screenshot", "photo"):
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="仅图片类有缩略图")

    thumb_path = path.parent / "thumbs" / f"{record['id']}{path.suffix.lower()}.thumb.jpg"
    if not _HAS_PIL:
        return FileResponse(path=path, media_type=_media_type_of(record["filename"]))
    if not thumb_path.is_file():
        try:
            thumb_path.parent.mkdir(parents=True, exist_ok=True)
            with Image.open(path) as im:
                im = im.convert("RGB") if im.mode != "RGB" else im
                im.thumbnail((THUMB_MAX_EDGE, THUMB_MAX_EDGE))
                im.save(thumb_path, "JPEG", quality=82)
            logger.info("生成缩略图：%s -> %s", path.name, thumb_path.name)
        except Exception as e:
            logger.warning("缩略图生成失败（降级原图）：%s，%s", path.name, e)
            return FileResponse(path=path, media_type=_media_type_of(record["filename"]))
    return FileResponse(path=thumb_path, media_type="image/jpeg")


def _authorize_media(request: Request, token: str | None) -> None:
    """媒体文件/缩略图端点的双通道鉴权（Bearer 头或 ?token= 查询参数）。"""
    admin_auth = request.app.state.admin_auth
    auth_header = request.headers.get("Authorization", "")
    if auth_header.startswith("Bearer ") and admin_auth.verify(auth_header[7:]):
        return
    if token and admin_auth.verify(token):
        return
    raise HTTPException(
        status.HTTP_401_UNAUTHORIZED,
        detail="未授权：请先登录管理后台",
        headers={"WWW-Authenticate": "Bearer"},
    )


def _media_type_of(filename: str) -> str:
    if filename.endswith(".mp4"):
        return "video/mp4"
    if filename.endswith(".png"):
        return "image/png"
    return "image/jpeg"


@router.delete("/{record_id}", status_code=status.HTTP_200_OK, dependencies=[Depends(require_admin)])
async def delete_media(record_id: int, request: Request) -> dict:
    """删除一条媒体记录连同磁盘文件（含缩略图缓存）。"""
    store = _get_store(request)
    if not store.delete(record_id):
        raise HTTPException(status.HTTP_404_NOT_FOUND, detail="媒体文件不存在")
    return {"status": "ok", "deleted": record_id}
