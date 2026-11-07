"""预热极空播报分段 TTS（v0.2.95，配合 /api/tts?seg=1 与客户端本地字节拼接）。

预合成 0..100 数字 + 播报词组，落盘到 server/data/tts_cache/（键与端点完全一致），
客户端首次播报即可全段命中，之后零网络本地拼接。

运行：``cd server && uv run python tts_prewarm.py``（幂等，已存在的段自动跳过）。
"""

from __future__ import annotations

import asyncio
import hashlib
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))

from app.api.v1.tts import _SEG_AF, _cache_dir, _loudnorm, DEFAULT_VOICE, SEG_SUFFIX  # noqa: E402

# 播报词组（客户端 JikongSpeaker 分段口径，顺序拼接成句）
# v0.2.98：去掉「百分之」——「当前电量」+「72」两段即可（edge-tts 读纯数字为「七十二」），
# 3 段 2 停顿 → 2 段 1 停顿；整句 fallback 仍含 %（edge-tts 整句自己读「百分之」）。
PHRASES = ["当前电量", "时速", "公里", "剩余续航", "电量低，请注意充电"]

# 0..100 数字（101 个，纯数字文本；>100 的数值由客户端按需拉取后同样进缓存）
TEXTS = [str(n) for n in range(0, 101)] + PHRASES


def seg_key(text: str, voice: str) -> str:
    # 键后缀从端点 import（SEG_SUFFIX），两端永不漂移（v0.2.97：v2seg→v3seg）
    return hashlib.md5(f"{text}|{voice}|{SEG_SUFFIX}".encode("utf-8")).hexdigest()


async def synth_one(text: str, voice: str, final: Path) -> None:
    import edge_tts

    raw = final.with_suffix(".raw.mp3")
    communicate = edge_tts.Communicate(text, voice=voice, volume="+0%")
    await asyncio.wait_for(communicate.save(str(raw)), timeout=30)
    if _loudnorm(raw, final, _SEG_AF):   # v0.2.97：裁段首静音+40ms 尾垫（与端点同链）
        raw.unlink(missing_ok=True)
    else:
        raw.replace(final)


async def main() -> None:
    d = _cache_dir()
    todo = []
    for t in TEXTS:
        f = d / f"{seg_key(t, DEFAULT_VOICE)}.mp3"
        if f.exists() and f.stat().st_size > 256:
            print(f"skip {t!r}（已有）")
        else:
            todo.append((t, f))
    print(f"共 {len(TEXTS)} 段，待合成 {len(todo)} 段…")
    for i, (t, f) in enumerate(todo, 1):
        try:
            await synth_one(t, DEFAULT_VOICE, f)
            print(f"[{i}/{len(todo)}] ok {t!r} {f.stat().st_size}B")
        except Exception as e:  # 单段失败不阻塞其余
            print(f"[{i}/{len(todo)}] FAIL {t!r}: {e}")
    print("预热完成。")


if __name__ == "__main__":
    asyncio.run(main())
