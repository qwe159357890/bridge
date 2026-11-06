# -*- coding: utf-8 -*-
"""拉取最新极空日志：握手帧/自愈循环/0x02 帧时间线全量分析。"""
import pymysql
import re
import datetime as dt

conn = pymysql.connect(
    host='86.74.128.24', user='root', password='123456',
    database='bridge', charset='utf8mb4',
)
cur = conn.cursor()


def loc(v):
    """时间戳转本地时间（兼容 datetime / UTC 毫秒）。"""
    if isinstance(v, dt.datetime):
        return v.strftime('%H:%M:%S')
    try:
        return (dt.datetime(1970, 1, 1) + dt.timedelta(seconds=float(v) / 1000 + 8 * 3600)).strftime('%H:%M:%S')
    except Exception:
        return repr(v)


# 1) 最新上传时间概览
cur.execute("SELECT MAX(created_at) FROM device_logs WHERE message LIKE '通知%' OR message LIKE '%Jikong%'")
row = cur.fetchone()
print('最新日志上传时刻:', loc(row[0]) if row and row[0] else '无')

# 2) 今天 17:00 之后的非通知诊断日志（按设备真实 ts 排序）
cur.execute(
    "SELECT ts, message FROM device_logs "
    "WHERE created_at >= '2026-11-06 17:00:00' AND message NOT LIKE '通知%' "
    "AND (tag LIKE 'Jikong%' OR message LIKE '%Jikong%' OR message LIKE '%握手%' "
    "OR message LIKE '%自愈%' OR message LIKE '%重连%' OR message LIKE '%0x02%' "
    "OR message LIKE '%GATT%' OR message LIKE '%MTU%' OR message LIKE '%CCCD%') "
    "ORDER BY id ASC"
)
rows = cur.fetchall()
print('---- 非通知诊断日志', len(rows), '条 ----')
for ts, msg in rows:
    print(loc(ts), '|', (msg or '').strip()[:160])

# 3) 通知流重建 0x02 帧时间线（看有没有帧、帧的设备时间分布）
cur.execute(
    "SELECT ts, message FROM device_logs "
    "WHERE created_at >= '2026-11-06 17:00:00' AND message LIKE '通知%' ORDER BY id ASC"
)
rows = cur.fetchall()
print('---- 通知行数', len(rows))
buf = bytearray()
frames = []
i = 0
for ts, msg in rows:
    m = re.search(r'([0-9A-Fa-f]{2}(?: [0-9A-Fa-f]{2})+)', msg or '')
    if not m:
        continue
    buf += bytes.fromhex(m.group(1).replace(' ', ''))
    while i <= len(buf) - 300:
        if (buf[i] == 0x55 and buf[i + 1] == 0xAA and buf[i + 2] == 0xEB
                and buf[i + 3] == 0x90 and buf[i + 4] in (1, 2, 3)):
            frames.append((ts, buf[i + 4]))
            i += 300
        else:
            i += 1
print('完整帧数', len(frames), '类型分布:', {t: sum(1 for _, x in frames if x == t) for t in (1, 2, 3)})
if frames:
    print('首帧设备时间', loc(frames[0][0]), '末帧', loc(frames[-1][0]))
    d02 = [t for t, ty in frames if ty == 2]
    if d02:
        gaps = [(a, b) for a, b in zip(d02, d02[1:]) if b - a > 4000]
        print('0x02 帧数', len(d02), '间隔>4s 次数', len(gaps))
        for a, b in gaps[:15]:
            print('   断流', loc(a), '->', loc(b), '%.1fs' % ((b - a) / 1000))
    # 0x01/0x03 帧时间（BMS 有无应答其它命令）
    for ty in (1, 3):
        seq = [t for t, x in frames if x == ty]
        if seq:
            print('0x%02X 帧数 %d, 首帧 %s 末帧 %s' % (ty, len(seq), loc(seq[0]), loc(seq[-1])))
conn.close()
