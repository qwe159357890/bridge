# -*- coding: utf-8 -*-
"""拉取 v0.2.81 首连自愈测试日志（~16:57 上传），重建诊断时间线"""
import pymysql, re, datetime as dt

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

cur.execute("SELECT MAX(created_at) FROM device_logs")
print('最新上传:', cur.fetchone()[0])

# 全部极空相关（含通知，通知已降噪应只有 3 条/会话）
cur.execute("""SELECT created_at, ts, tag, message FROM device_logs
WHERE created_at >= '2026-11-06 16:50:00' AND (tag LIKE 'Jikong%' OR message LIKE '%Jikong%')
ORDER BY id ASC""")
rows = cur.fetchall()
print('16:50 后极空相关条数', len(rows))
notif = 0
for cat, ts, tag, msg in rows:
    m = (msg or '').strip()
    if m.startswith('通知'):
        notif += 1
        continue
    print(loc(cat), '| dev=', loc(ts) if ts else '-', '|', m[:150])
print('（通知条数', notif, '）')
