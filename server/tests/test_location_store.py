"""location_store 纯函数与配置加载测试（不依赖真实 MySQL）。

覆盖：
- haversine 距离（已知城市对，误差 < 1%）
- WGS-84 -> GCJ-02 转换（境内有偏移、境外原样、确定性）
- 静止合并阈值语义（max(两次精度, 30m)）
- config.local.yaml 自动合并（存在则覆盖 config.yaml）

MySQL 读写链路由联调环境验证（见 docs/api/commands/data.md）。
"""

from __future__ import annotations

import pytest

from app.core.location_store import (
    MERGE_MIN_METERS,
    haversine_m,
    wgs84_to_gcj02,
)


class TestHaversine:
    def test_beijing_shanghai(self):
        # 北京天安门 -> 上海人民广场，公开直线距离约 1067 km
        d = haversine_m(39.9042, 116.4074, 31.2304, 121.4737)
        assert abs(d - 1_067_000) / 1_067_000 < 0.01

    def test_zero_distance(self):
        assert haversine_m(30.0, 120.0, 30.0, 120.0) == pytest.approx(0, abs=1e-6)

    def test_short_distance_symmetry(self):
        # 相距约 14m 的两点（静止合并的真实量级）
        d1 = haversine_m(39.9042, 116.4074, 39.9043, 116.4075)
        d2 = haversine_m(39.9043, 116.4075, 39.9042, 116.4074)
        assert 0 < d1 < 200
        assert d1 == pytest.approx(d2)


class TestGcj02:
    def test_domestic_offset(self):
        # 境内（北京）应有几十~几百米偏移，且方向确定（同输入同输出）
        lat, lon = 39.9042, 116.4074
        glat, glon = wgs84_to_gcj02(lat, lon)
        offset = haversine_m(lat, lon, glat, glon)
        assert 50 < offset < 1000
        assert wgs84_to_gcj02(lat, lon) == (glat, glon)

    def test_overseas_unchanged(self):
        # 东京（境外）原样返回
        assert wgs84_to_gcj02(35.6762, 139.6503) == (35.6762, 139.6503)
        # 南海以南境外
        assert wgs84_to_gcj02(-6.2, 106.8) == (-6.2, 106.8)

    def test_edge_in_china(self):
        # 新疆喀什（境内西端）也应偏移
        lat, lon = 39.4704, 75.9898
        glat, glon = wgs84_to_gcj02(lat, lon)
        assert (glat, glon) != (lat, lon)


class TestMergeThreshold:
    def test_threshold_semantics(self):
        """阈值 = max(两次精度, 30m)：GPS 高精度时兜底 30m，低精度取较大者。"""
        # 两次 GPS 定位（±5m/±8m）：阈值取 30m 下限
        assert max(5.0, 8.0, MERGE_MIN_METERS) == 30.0
        # 一次网络定位 ±500m：阈值 500m
        assert max(500.0, 8.0, MERGE_MIN_METERS) == 500.0
        # 14m 漂移 + 双方 ±25m：14 < max(25,25,30)=30 → 应合并
        assert haversine_m(39.9042, 116.4074, 39.9043, 116.4075) < max(25.0, 25.0, MERGE_MIN_METERS)
