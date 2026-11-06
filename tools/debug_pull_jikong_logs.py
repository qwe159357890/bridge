# -*- coding: utf-8 -*-
"""拉取今日 14:00 后的极空连接日志（验证 v0.2.79/80 行为）"""
import pymysql, datetime, re

conn = pymysql.connect(host='86.74.128.24', user='root', password='123456',
                       database='bridge', charset='utf8mb4')
cur = conn.cursor()

cur.execute("SELECT MIN(created_at), MAX(created_at), COUNT(*) FROM device_logs WHERE created_at >= '2026-11-06 12:00:00'")
lo, hi, n = cur.fetchone()
fmt = lambda ts: datetime.datetime.fromtimestamp(ts).strftime('%H:%M:%S') if ts else '-'
print('今日12点后: 最早 %s 最新 %s 条数 %d' % (fmt(lo), fmt(hi), n))

cur.execute("""SELECT created_at, tag, LEFT(message,150) FROM device_logs
WHERE created_at >= '2026-11-06 14:00:00' AND (tag LIKE 'Jikong%' OR message LIKE '%Jikong%')
ORDER BY id ASC""")
rows = cur.fetchall()
print('14点后极空相关条数', len(rows))
for ts, tag, msg in rows[:80]:
    print(fmt(ts), '|', tag, '|', (msg or '').strip()[:130])

# 若有通知流，重建 0x02 帧时间线（首帧延迟/断流）
cur.execute("""SELECT created_at, ts, message FROM device_logs
WHERE created_at >= '2026-11-06 14:00:00' AND message LIKE '通知%'
ORDER BY id ASC""")
notif = cur.fetchall()
print('\n通知行数', len(notif))
buf = bytearray()
frames = []  # (wall_clock_ts, type)
for cat, ts, msg in notif:
    m = re.search(r'([0-9A-Fa-f]{2}(?: [0-9A-Fa-f]{2})+)', msg or '')
    if not m:
        continue
    buf += bytes.fromhex(m.group(1).replace(' ', ''))
i = 0
while i <= len(buf) - 300:
    if buf[i] == 0x55 and buf[i+1] == 0xAA and buf[i+2] == 0xEB and buf[i+3] == 0x90 and buf[i+4] in (1, 2, 3):
        frames.append((cat, buf[i+4]))
        i += 300
    else:
        i += 1
types = {}
for _, t in frames:
    types[t] = types.get(t, 0) + 1
print('完整帧数', len(frames), '各类型:', types)
if frames:
    print('首帧时间: %s 类型 %d' % (fmt(frames[0][0]), frames[0][1]))
    # 0x02 帧间隔超过 4s 的位置
    data_ts = [t for t, ty in frames if ty == 2]
    gaps = []
    for a, b in zip(data_ts, data_ts[1:]):
        if b - a > 4:
            gaps.append((fmt(a), fmt(b), b - a))
    print('0x02 帧数', len(data_ts), '间隔>4s 的段数', len(gaps))
    for g in gaps[:10]:
        print('  断流 %s -> %s (%ds)' % g)
