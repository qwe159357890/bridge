# -*- coding: utf-8 -*-
"""按设备真实时间 ts 拉 18:18 官方握手测试会话的完整时间线 + 帧统计。"""
import pymysql
import re
import datetime as dt

conn = pymysql.connect(
    host='86.74.128.24', user='root', password='123456',
    database='bridge', charset='utf8mb4',
)
cur = conn.cursor()
CUT = 1794075480000  # 2026-11-06 18:18:00 CST


def loc(v):
    if isinstance(v, dt.datetime):
        return v.strftime('%H:%M:%S')
    try:
        return (dt.datetime(1970, 1, 1) + dt.timedelta(seconds=float(v) / 1000 + 8 * 3600)).strftime('%H:%M:%S')
    except Exception:
        return repr(v)


cur.execute(
    "SELECT ts, message FROM device_logs "
    "WHERE created_at >= '2026-11-07 00:00:00' AND message NOT LIKE '通知%%' "
    "AND ts > %s AND (tag LIKE 'Jikong%%' OR message LIKE '%%GATT%%' OR message LIKE '%%握手%%' "
    "OR message LIKE '%%重连%%' OR message LIKE '%%0x02%%' OR message LIKE '%%自愈%%' "
    "OR message LIKE '%%MTU%%' OR message LIKE '%%演示%%' OR message LIKE '%%连接状态%%') "
    "ORDER BY ts ASC",
    (CUT,),
)
rows = cur.fetchall()
print('---- 设备时间 18:18 后诊断日志', len(rows))
for ts, msg in rows:
    print(loc(ts), '|', (msg or '').strip()[:130])

cur.execute(
    "SELECT ts, message FROM device_logs "
    "WHERE created_at >= '2026-11-07 00:00:00' AND message LIKE '通知%%' AND ts > %s "
    "ORDER BY ts ASC",
    (CUT,),
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
print('完整帧数', len(frames), '类型:', {t: sum(1 for _, x in frames if x == t) for t in (1, 2, 3)})
d02 = [t for t, ty in frames if ty == 2]
if d02:
    print('0x02: 首帧', loc(d02[0]), '末帧', loc(d02[-1]), '共', len(d02))
conn.close()
