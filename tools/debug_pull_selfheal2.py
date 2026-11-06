# -*- coding: utf-8 -*-
"""查通知日志来源 + 16:57:28 之后的完整时间线"""
import pymysql, datetime as dt

conn = pymysql.connect(host='86.74.128.24', user='root', password='123456',
                       database='bridge', charset='utf8mb4')
cur = conn.cursor()

def loc(v):
    try:
        if isinstance(v, dt.datetime):
            return v.strftime('%H:%M:%S')
        return (dt.datetime(1970, 1, 1) + dt.timedelta(seconds=float(v) / 1000 + 8 * 3600)).strftime('%H:%M:%S')
    except Exception:
        return repr(v)

# 1) 通知行的 tag 分布与设备时间范围
cur.execute("SELECT tag, COUNT(*), MIN(ts), MAX(ts) FROM device_logs WHERE created_at >= '2026-11-06 16:50:00' AND message LIKE '通知%' GROUP BY tag")
for tag, n, lo, hi in cur.fetchall():
    print('通知 tag=%s 条数 %d 设备时间 %s -> %s' % (tag, n, loc(lo), loc(hi)))

# 2) 16:57:28 之后的非通知日志
cur.execute("SELECT ts, message FROM device_logs WHERE created_at >= '2026-11-06 16:50:00' AND message NOT LIKE '通知%' AND ts > 1793955448000 ORDER BY ts ASC")
rows = cur.fetchall()
print('---- 16:57:28 后非通知', len(rows))
for ts, msg in rows[:70]:
    print(loc(ts), '|', (msg or '').strip()[:140])

# 3) 通知行按设备时间分桶（每 5 秒计数，看数据流存活区间）
cur.execute("SELECT ts FROM device_logs WHERE created_at >= '2026-11-06 16:50:00' AND message LIKE '通知%' ORDER BY ts ASC")
ts_list = [r[0] for r in cur.fetchall()]
if ts_list:
    buckets = {}
    for t in ts_list:
        key = int(t // 5000) * 5
        buckets[key] = buckets.get(key, 0) + 1
    print('---- 通知按设备时间 5s 分桶')
    for k in sorted(buckets):
        print(loc(k * 1000), buckets[k])
