"""edge-tts 在线语音合成端点（极空播报 v0.2.90）。

- ``GET /api/tts?text=..&voice=..``   设备头鉴权（与 odometer/music 同一套），
  edge-tts 合成 mp3 → ffmpeg loudnorm 响度归一化（I=-12 LUFS，比静态增益稳、不削波）
  → 本地缓存落盘（同文案同音色只合成一次）→ 返回音频。

缓存目录 ``server/data/tts_cache/``（与 updates 目录同范式解析）；客户端另有
``cacheDir/tts/`` 二级缓存（同一文案只请求一次，LRU 50MB）。

依赖：``uv add edge-tts``（pyproject.toml）；ffmpeg 需在 PATH（响度归一化用，
失败时自动降级返回 edge-tts 原始输出）。
"""

from __future__ import annotations

import asyncio
import hashlib
import logging
import re
import subprocess
from pathlib import Path

from fastapi import APIRouter, HTTPException, Request, status
from fastapi.responses import FileResponse

from app.api.v1.jikong import _check_device_auth

logger = logging.getLogger(__name__)

router = APIRouter(tags=["tts"])

DEFAULT_VOICE = "zh-CN-XiaoxiaoNeural"
VOICE_RE = re.compile(r"^[A-Za-z]{2,5}-[A-Za-z]{2,8}-[A-Za-z]+$")
MAX_TEXT_LEN = 120

# 响度归一化目标：v0.2.92 用户实测 -12 LUFS 偏小，提档到 -8 LUFS（语音类不削波上限附近，
# TP 仍留 1.5dB 余量）。缓存键同步升 v2（旧 -12 文件不再生效）。
_LOUDNORM = "loudnorm=I=-8:TP=-1.5:LRA=7"


def _cache_dir() -> Path:
    """server/data/tts_cache（与 updates 目录同范式：相对 server 根解析）。"""
    root = Path(__file__).resolve().parent.parent.parent.parent
    d = root / "data" / "tts_cache"
    d.mkdir(parents=True, exist_ok=True)
    return d


def _loudnorm(src: Path, dst: Path, af: str = _LOUDNORM) -> bool:
    """ffmpeg 滤链响度归一化（可追加 apad 尾静音）；失败返回 false（调用方降级用原始输出）。"""
    try:
        r = subprocess.run(
            ["ffmpeg", "-y", "-loglevel", "error", "-i", str(src),
             "-af", af, "-b:a", "48k", str(dst)],
            capture_output=True, timeout=30,
        )
        return r.returncode == 0 and dst.exists() and dst.stat().st_size > 256
    except Exception as e:  # ffmpeg 缺失/超时等
        logger.warning("tts 响度归一化失败（降级原始输出）：%s", e)
        return False


@router.get("/tts")
async def synth_tts(
    request: Request,
    text: str,
    voice: str = DEFAULT_VOICE,
    seg: int = 0,
) -> FileResponse:
    """合成一段中文语音（mp3）。同 (text, voice, seg) 只合成一次，之后走缓存。

    v0.2.95 分段模式（seg=1）：为客户端本地字节拼接服务——合成后追加 120ms 尾部静音
    （apad），拼接多段时句间有自然停顿；缓存键独立为 ``v2seg``，与整句缓存互不干扰。
    0..100 数字与常用词组由 ``server/tts_prewarm.py`` 预热，超出部分客户端按需拉取。
    """
    _check_device_auth(
        request,
        request.headers.get("x-device-id"),
        request.headers.get("x-device-token"),
    )
    text = (text or "").strip()
    if not text or len(text) > MAX_TEXT_LEN:
        raise HTTPException(status.HTTP_400_BAD_REQUEST, detail="text 为空或超过 120 字")
    if not VOICE_RE.match(voice):
        raise HTTPException(status.HTTP_400_BAD_REQUEST, detail="voice 名不合法")

    # v2 = 响度档版本（v0.2.92 由 -12 提到 -8 LUFS）；v2seg = 分段档（含尾静音）
    suffix = "v2seg" if seg else "v2"
    key = hashlib.md5(f"{text}|{voice}|{suffix}".encode("utf-8")).hexdigest()
    final = _cache_dir() / f"{key}.mp3"
    if not final.exists() or final.stat().st_size < 256:
        raw = final.with_suffix(".raw.mp3")
        try:
            import edge_tts

            communicate = edge_tts.Communicate(text, voice=voice, volume="+0%")
            await asyncio.wait_for(communicate.save(str(raw)), timeout=30)
        except Exception as e:
            logger.warning("edge-tts 合成失败：%s", e)
            raise HTTPException(
                status.HTTP_503_SERVICE_UNAVAILABLE, detail="在线语音合成暂不可用",
            ) from e
        af = _LOUDNORM + (",apad=pad_dur=0.12" if seg else "")
        if _loudnorm(raw, final, af):
            raw.unlink(missing_ok=True)
        else:
            raw.replace(final)  # 降级：无 ffmpeg 时直接用 edge 原始输出
        logger.info("tts 合成落盘：%s（%d B）", final.name, final.stat().st_size)
    return FileResponse(final, media_type="audio/mpeg")
