"""快速校验 FLV tag 结构（定位 mpegts.js FormatError）。"""
import sys
from pathlib import Path

path = Path(sys.argv[1] if len(sys.argv) > 1 else
            "server/data/media/f2c38ebee251a196/20261027_171744_182693_screen_live.flv")
data = path.read_bytes()
print(f"文件 {path.name}，{len(data)} 字节")
assert data[:3] == b"FLV", "缺少 FLV 头"
print(f"版本={data[3]} flags={data[4]:#04x} dataOffset={int.from_bytes(data[5:9],'big')}")

pos = int.from_bytes(data[5:9], "big")
pos += 4  # PreviousTagSize0
n = 0
issues = []
last_ts = -1
while pos + 11 <= len(data):
    ttype = data[pos]
    size = int.from_bytes(data[pos+1:pos+4], "big")
    ts = int.from_bytes(data[pos+4:pos+7], "big") + (data[pos+7] << 24)
    sid = int.from_bytes(data[pos+8:pos+11], "big")
    if pos + 11 + size > len(data):
        issues.append(f"tag#{n} 越界：声明 {size} 字节但只剩 {len(data)-pos-11}")
        break
    body = data[pos+11:pos+11+size]
    detail = ""
    if ttype == 0x09 and size >= 5:
        detail = f"frameType={body[0]>>4} codec={body[0]&0xf} avcPkt={body[1]} compOff={int.from_bytes(body[2:5],'big',signed=True)}"
    elif ttype == 0x12:
        detail = "script"
    if n < 6 or n % 50 == 0:
        print(f"tag#{n} type={ttype:#04x} size={size} ts={ts} sid={sid} {detail}")
    if sid != 0:
        issues.append(f"tag#{n} StreamID={sid}!=0")
    if last_ts >= 0 and ts < last_ts:
        issues.append(f"tag#{n} 时间戳回退：{ts} < {last_ts}")
    last_ts = ts
    pos += 11 + size + 4
    n += 1
print(f"\n共 {n} 个 tag；尾部剩余 {len(data)-pos} 字节")
print("问题：" + ("；".join(issues) if issues else "无（结构完整、时间戳单调）"))
