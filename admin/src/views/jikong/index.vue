<template>
  <div>
    <!-- 顶部工具栏（手机壳之外，管理台功能） -->
    <el-card shadow="never" class="toolbar-card">
      <div class="toolbar">
        <span class="title">极空保护板 <span class="title-sub">（手机端 1:1 复刻 · 数据实时同步自设备）</span></span>
        <div class="tools">
          <el-switch v-model="autoRefresh" active-text="自动刷新" size="small" style="margin-right: 8px" />
          <el-select
            v-model="selectedDevice"
            placeholder="选择设备"
            size="small"
            style="width: 240px"
            @change="onDeviceChange"
          >
            <el-option
              v-for="d in deviceOptions"
              :key="d.value"
              :label="d.label"
              :value="d.value"
            />
          </el-select>
          <el-button :icon="Refresh" size="small" @click="refreshAll">刷新</el-button>
        </div>
      </div>
    </el-card>

    <!-- 未选设备 -->
    <el-empty v-if="!selectedDevice" description="请先选择一台设备" :image-size="80" />

    <template v-else>
      <div class="stage">
        <!-- ============ 手机壳（与客户端 JikongPages.kt 1:1） ============ -->
        <div class="phone" :class="{ light: themeLight }">
          <div class="screen">
            <!-- 内容区（滚动） -->
            <div class="content">
              <!-- ============ Tab 0：首页 ============ -->
              <template v-if="tab === 0">
                <template v-if="connected && s">
                  <!-- 连接状态栏（首页顶部，可滚动回看） -->
                  <div class="jk-connbar">
                    <span class="dot" :class="connected ? 'g' : 'gy'" />
                    <span class="conn-text" :class="connected ? 'g-text' : 'gy'">已连接 · {{ s.device_name || selectedDevice }}</span>
                    <span class="theme-btn" @click="themeLight = !themeLight">{{ themeLight ? '浅色' : '深色' }}</span>
                  </div>

                  <!-- 充电/放电/均衡 一行三列 -->
                  <div class="jk-statusrow">
                    <div class="jk-statuscell">
                      <span class="dot" :class="charging ? 'g' : 'r'" /><span>充电</span>
                      <span :class="charging ? 'g-text' : 'r-text'">{{ charging ? '开启' : '关闭' }}</span>
                    </div>
                    <div class="vdiv" />
                    <div class="jk-statuscell">
                      <span class="dot" :class="discharging ? 'g' : 'r'" /><span>放电</span>
                      <span :class="discharging ? 'g-text' : 'r-text'">{{ discharging ? '开启' : '关闭' }}</span>
                    </div>
                    <div class="vdiv" />
                    <div class="jk-statuscell">
                      <span class="dot" :class="s.balance_on ? 'g' : 'r'" /><span>均衡</span>
                      <span :class="s.balance_on ? 'g-text' : 'r-text'">{{ s.balance_on ? '开启' : '关闭' }}</span>
                    </div>
                  </div>

                  <!-- 中央仪表盘（300°弧 + 0~100 刻度带 + 外围数字 + SOC + 三胶囊） -->
                  <div class="gauge-wrap">
                    <svg viewBox="0 0 320 320" class="gauge-svg">
                      <defs>
                        <linearGradient id="gaugeGrad" gradientUnits="userSpaceOnUse" x1="34" y1="160" x2="286" y2="160">
                          <stop offset="0" :stop-color="c.g1" />
                          <stop offset="0.5" :stop-color="c.g2" />
                          <stop offset="1" :stop-color="c.g3" />
                        </linearGradient>
                      </defs>
                      <!-- 灰色轨道（300°，缺口朝下） -->
                      <path :d="gaugeTrackPath" fill="none" :stroke="c.track" stroke-width="14" />
                      <!-- 渐变进度弧 -->
                      <path v-if="gaugeSweep > 0.5" :d="gaugeProgressPath" fill="none" stroke="url(#gaugeGrad)" stroke-width="14" />
                      <!-- 内圈刻度带 0~100 -->
                      <line
                        v-for="t in gaugeTicks" :key="'t' + t.i"
                        :x1="t.x1" :y1="t.y1" :x2="t.x2" :y2="t.y2"
                        :stroke="c.green" :stroke-width="t.w" stroke-linecap="round"
                      />
                      <!-- 外围刻度数字（逢5） -->
                      <text
                        v-for="n in gaugeNums" :key="'n' + n.i"
                        :x="n.x" :y="n.y" text-anchor="middle"
                        :font-size="n.major ? 11 : 9"
                        :font-weight="n.major ? 700 : 400"
                        :fill="n.major ? c.numA : c.numB"
                      >{{ n.i }}</text>
                    </svg>
                    <!-- 中央内容 -->
                    <div class="gauge-center">
                      <div class="soc-num" :style="{ color: c.green }">{{ soc }}%</div>
                      <div class="pill-row">
                        <span class="pill-outline" :style="pillOutline">{{ fmt(s.total_voltage) }}V</span>
                        <span class="pill-outline" :style="pillOutline">{{ fmt(s.current) }}A</span>
                      </div>
                      <span class="pill-fill" :style="{ background: c.green, color: c.onfill }">{{ fmt(powerNow) }}W</span>
                    </div>
                  </div>

                  <!-- 状态提示条 -->
                  <div class="jk-health">
                    <span class="health-check" :style="{ background: c.green }">
                      <svg viewBox="0 0 24 24" width="12" height="12"><path d="M5 12l5 5L19 7" fill="none" stroke="#fff" stroke-width="3" stroke-linecap="round"/></svg>
                    </span>
                    <span>电池运行良好</span>
                  </div>

                  <!-- 时速仪表盘卡片（开关关闭时与手机一致不渲染） -->
                  <div v-if="dashEnabled && s.dash" class="jk-card dash-card">
                    <div class="dash-row1">
                      <div class="dash-col">
                        <div class="mini-label">时速(km/h)</div>
                        <div class="dash-big" :style="{ color: c.green }">{{ s.dash.gps_fresh ? n1(s.dash.speed_kmh) : '--' }}</div>
                      </div>
                      <div class="dash-col">
                        <div class="mini-label">剩余续航(km)</div>
                        <div class="dash-big" :style="{ color: s.dash.bms_stale ? c.gray : c.blue }">
                          {{ s.dash.range_km != null && s.dash.range_km >= 0 ? Math.round(s.dash.range_km) : '--' }}
                        </div>
                      </div>
                    </div>
                    <div class="hdiv" />
                    <div class="dash-row2">
                      <div class="dash-col">
                        <div class="mini-label">瞬时(Wh/km)</div>
                        <div class="dash-mid">{{ gt0(s.dash.inst_wh_km) ? n1(s.dash.inst_wh_km) : '--' }}</div>
                      </div>
                      <div class="dash-col">
                        <div class="mini-label">平均(Wh/km)</div>
                        <div class="dash-mid">{{ gt0(s.dash.avg_wh_km) ? n1(s.dash.avg_wh_km) : '--' }}</div>
                      </div>
                      <div class="dash-col">
                        <div class="mini-label">本次里程(km)</div>
                        <div class="dash-mid">{{ gt0(s.dash.trip_km) ? n2(s.dash.trip_km) : '--' }}</div>
                      </div>
                    </div>
                    <div class="dash-row3">
                      <span class="mini-label">本次时长 {{ fmtDuration(s.dash.trip_seconds || 0) }}</span>
                    </div>
                    <div v-if="s.dash.bms_stale" class="dash-warn" :style="{ color: c.amber }">保护板数据未到，续航估算暂停</div>
                  </div>

                  <!-- 数据网格一 -->
                  <div class="jk-quad">
                    <template v-for="(cell, i) in quad1" :key="cell.label">
                      <div v-if="i > 0" class="vdiv" style="height: 30px" />
                      <div class="quad-cell">
                        <div class="quad-val" :style="{ color: cell.color }">{{ cell.value }}</div>
                        <div class="mini-label">{{ cell.label }}</div>
                      </div>
                    </template>
                  </div>
                  <!-- 数据网格二 -->
                  <div class="jk-quad">
                    <template v-for="(cell, i) in quad2" :key="cell.label">
                      <div v-if="i > 0" class="vdiv" style="height: 30px" />
                      <div class="quad-cell">
                        <div class="quad-val" :style="{ color: cell.color }">{{ cell.value }}</div>
                        <div class="mini-label">{{ cell.label }}</div>
                      </div>
                    </template>
                  </div>

                  <!-- 电流/功率卡片 -->
                  <div class="jk-card cp-card">
                    <div class="cp-row">
                      <span class="cp-label">电流(A)</span>
                      <span class="cp-val" :style="{ color: c.green }">{{ hasData ? fmt(s.current) : '--' }}</span>
                      <span class="cp-label" style="margin-left: 22px">功率(W)</span>
                      <span class="cp-val" :style="{ color: c.green }">{{ hasData ? fmt(powerNow) : '--' }}</span>
                    </div>
                    <div class="cp-ends">
                      <span class="mini-label">充电</span>
                      <span class="cp-zero">0</span>
                      <span class="mini-label">放电</span>
                    </div>
                    <!-- 双向进度条 -->
                    <div class="cp-bar">
                      <div class="cp-bar-track" :style="{ background: c.track }" />
                      <div class="cp-half cp-half-l">
                        <div
                          class="cp-fill-charge"
                          :style="{
                            width: (chargeFrac * 100) + '%',
                            background: `linear-gradient(to right, ${c.cyan}, ${c.green})`,
                          }"
                        />
                      </div>
                      <div class="cp-half cp-half-r">
                        <div class="cp-fill-discharge" :style="{ width: dischFrac * 100 + '%', background: c.red }" />
                      </div>
                      <div class="cp-center" :style="{ background: c.text }" />
                    </div>
                    <div class="cp-status">
                      <span class="cp-label">状态:</span>
                      <span class="cp-state" :style="{ color: c.green }">{{ charging ? '充电' : discharging ? '放电' : '静止' }}</span>
                      <span style="flex: 1" />
                      <span class="cp-label">剩余时间:</span>
                      <span class="cp-state" :style="{ color: c.green }">{{ remainTimeText }}</span>
                    </div>
                  </div>
                </template>

                <!-- 未连接占位（与手机扫描页同款语义） -->
                <div v-else class="offline">
                  <div class="offline-icon">🔋</div>
                  <div class="offline-text">设备未连接极空保护板</div>
                  <div class="offline-sub">请在手机上打开极空页并连接保护板，连接后此处自动同步手机画面</div>
                </div>
              </template>

              <!-- ============ Tab 1：实时状态 ============ -->
              <template v-else-if="tab === 1">
                <template v-if="connected && s">
                  <div class="jk-card" style="border-radius: 10px; padding: 6px 14px">
                    <span class="cp-label">电池电压(V)</span>
                    <span class="cp-val" :style="{ color: c.green }">{{ fmt(s.total_voltage) }}</span>
                    <span class="cp-label" style="margin-left: 22px">电池电流(A)</span>
                    <span class="cp-val" :style="{ color: c.green }">{{ fmt(s.current) }}</span>
                  </div>

                  <div class="jk-section">
                    <span class="sec-dot" /><span class="sec-title">实时数据</span>
                  </div>
                  <div class="jk-card" style="border-radius: 10px">
                    <div class="detail-cols">
                      <div class="detail-col">
                        <div class="drow"><span class="dlabel">电池功率</span><span class="dval">{{ fmt(powerNow) }}W</span></div>
                        <div class="drow"><span class="dlabel">电池容量</span><span class="dval">{{ fmt(s.nominal_capacity) }}Ah</span></div>
                        <div class="drow"><span class="dlabel">剩余容量</span><span class="dval">{{ fmt(s.remain_capacity) }}Ah</span></div>
                        <div class="drow"><span class="dlabel">功率温度</span><span class="dval">{{ tempAt(0) }}</span></div>
                        <div class="drow"><span class="dlabel">电池温度1</span><span class="dval">{{ tempAt(1) }}</span></div>
                        <div class="drow"><span class="dlabel">电池温度2</span><span class="dval">{{ tempAt(2) }}</span></div>
                        <div class="drow"><span class="dlabel">加热状态</span><span class="dval">{{ s.heater_on ? '开启' : '关闭' }}</span></div>
                        <div class="drow"><span class="dlabel">均衡</span><span class="dval">{{ s.balance_on ? '开启' : '关闭' }}</span></div>
                      </div>
                      <div class="detail-col">
                        <div class="drow"><span class="dlabel">单体平均</span><span class="dval">{{ fmt(s.avg_cell_voltage) }}V</span></div>
                        <div class="drow"><span class="dlabel">最大压差</span><span class="dval">{{ fmt(s.diff_voltage) }}V</span></div>
                        <div class="drow"><span class="dlabel">均衡电流</span><span class="dval">{{ fmt(s.balance_current) }}A</span></div>
                        <div class="drow"><span class="dlabel">循环次数</span><span class="dval">{{ s.cycle_count ?? 0 }}次</span></div>
                        <div class="drow"><span class="dlabel">循环容量</span><span class="dval">{{ fmt(s.cycle_capacity) }}Ah</span></div>
                        <div class="drow"><span class="dlabel">加热电流</span><span class="dval">{{ fmt(s.heater_current) }}A</span></div>
                        <div class="drow"><span class="dlabel">充电器状态</span><span class="dval">{{ s.charger_state || '未插入' }}</span></div>
                        <div class="drow"><span class="dlabel">电池类型</span><span class="dval">{{ s.battery_type || '--' }}</span></div>
                      </div>
                    </div>
                  </div>

                  <div class="jk-section">
                    <span class="sec-dot" /><span class="sec-title">单体电压 (V)</span>
                  </div>
                  <div class="jk-card tri-grid">
                    <div v-for="r in triRows(s.cell_voltages, true)" :key="'cv' + r.i" class="tri-cell">
                      <span class="tri-badge" :style="{ background: c.badge, color: c.badgeText }">{{ r.num }}</span>
                      <span
                        class="tri-val"
                        :style="{ color: r.color }"
                      >{{ r.text }}</span>
                    </div>
                  </div>

                  <div class="jk-section">
                    <span class="sec-dot" /><span class="sec-title">均衡线电阻 (mΩ)</span>
                  </div>
                  <div class="jk-card tri-grid">
                    <div v-for="r in triRows(s.cell_resistances, false)" :key="'cr' + r.i" class="tri-cell">
                      <span class="tri-badge" :style="{ background: c.badge, color: c.badgeText }">{{ r.num }}</span>
                      <span class="tri-val" :style="{ color: r.color }">{{ r.text }}</span>
                    </div>
                  </div>
                </template>
                <div v-else class="offline">
                  <div class="offline-icon">📊</div>
                  <div class="offline-text">暂无实时数据</div>
                  <div class="offline-sub">设备连接保护板后显示</div>
                </div>
              </template>

              <!-- ============ Tab 2：统计（只读，录入仍在手机端） ============ -->
              <template v-else-if="tab === 2">
                <div class="stats-summary">
                  <div class="sum-card" :style="{ background: c.card }">
                    <div class="sum-label">区间里程(km)</div>
                    <div class="sum-val">{{ odo ? odo.summary.total_km.toFixed(1) : '--' }}</div>
                  </div>
                  <div class="sum-card" :style="{ background: c.card }">
                    <div class="sum-label">总耗电(kWh)</div>
                    <div class="sum-val">{{ odo ? odo.summary.total_kwh.toFixed(2) : '--' }}</div>
                  </div>
                  <div class="sum-card" :style="{ background: c.card }">
                    <div class="sum-label">平均电耗(Wh/km)</div>
                    <div class="sum-val">{{ odo && odo.summary.avg_wh_km != null ? odo.summary.avg_wh_km.toFixed(1) : '--' }}</div>
                  </div>
                </div>

                <div class="jk-card" style="padding: 12px 14px">
                  <div class="stats-head">
                    <span style="font-weight: 600">能耗曲线</span>
                    <span class="mini-label">Wh/km · 只读（录入在手机端）</span>
                  </div>
                  <svg v-if="curvePoints.length >= 2" viewBox="0 0 330 150" class="curve-svg">
                    <line x1="8" y1="20" x2="322" y2="20" :stroke="c.gray" stroke-width="1" stroke-dasharray="4 4" opacity="0.6" />
                    <text :x="322" y="14" text-anchor="end" :fill="c.gray" font-size="9">均值 {{ curveAvg }}</text>
                    <polyline :points="curvePolyline" fill="none" :stroke="c.green" stroke-width="2" stroke-linejoin="round" />
                    <circle v-for="(p, i) in curvePoints" :key="i" :cx="p.x" :cy="p.y" r="3" :fill="c.green" />
                    <text v-for="(p, i) in curvePoints" :key="'v' + i" :x="p.x" :y="p.y - 7" text-anchor="middle" :fill="c.text" font-size="9">{{ p.v }}</text>
                  </svg>
                  <div v-else class="empty-mini">有效能耗段不足（需至少两条记录且容量递增）</div>
                </div>

                <div class="jk-card" style="padding: 12px 0 0">
                  <div class="stats-head" style="padding: 0 14px 8px">
                    <span style="font-weight: 600">记录台账</span>
                    <span class="mini-label">{{ odo ? odo.records.length : 0 }} 条 · 新在上 · MySQL</span>
                  </div>
                  <div class="rec-list">
                    <div v-for="(rec, i) in odoRecordsView" :key="rec.record_id" class="rec-row">
                      <div class="rec-date">{{ rec.date }}<br>{{ rec.time }}<span v-if="rec.isBase" class="rec-base">基准</span></div>
                      <div class="rec-odo">
                        {{ rec.odo.toFixed(1) }}
                        <small>{{ rec.isBase ? '第一条记录' : `距上次 +${rec.dKm.toFixed(1)} km` }}</small>
                      </div>
                      <div class="rec-delta">
                        <template v-if="rec.seg">{{ rec.seg.d_ah.toFixed(1) }} Ah · {{ rec.seg.avg_volt.toFixed(1) }} V<br>{{ rec.seg.kwh.toFixed(2) }} kWh</template>
                        <template v-else-if="!rec.isBase && rec.invalid">数据无效段</template>
                        <template v-else>—</template>
                      </div>
                      <div class="rec-wh" :style="{ color: rec.whColor }">
                        {{ rec.seg ? rec.seg.wh_km.toFixed(1) : '--' }}<small v-if="rec.seg">Wh/km</small>
                      </div>
                    </div>
                    <div v-if="!odoRecordsView.length" class="empty-mini">暂无记录（在手机端统计页录入）</div>
                  </div>
                </div>
                <div class="stats-note">
                  口径：段电耗 Wh/km = Δ循环容量 × 两记录平均电压 ÷ Δ里程。循环容量只增不减，充电不影响；Δ容量≤0（换电池/BMS 重置）该段无效不进曲线。
                </div>
              </template>

              <!-- ============ Tab 3：参数设置（只读镜像） ============ -->
              <template v-else>
                <div class="set-title">仪表盘</div>
                <div class="jk-card" style="border-radius: 12px">
                  <div class="set-row">
                    <div style="flex: 1">
                      <div style="font-size: 14px; font-weight: 600">时速仪表盘</div>
                      <div class="mini-label" style="margin-top: 2px">
                        首页显示时速/续航/电耗卡片。使用 GPS 定位（仅页面打开时工作），会增加少量耗电；关闭后完全停止。
                      </div>
                    </div>
                    <el-switch v-model="dashMirror" disabled size="small" style="margin-left: 8px" />
                  </div>
                </div>

                <div class="set-title">累计统计</div>
                <div class="jk-card" style="border-radius: 12px">
                  <div class="dash-row2" style="padding: 0">
                    <div class="dash-col">
                      <div class="mini-label">累计里程(km)</div>
                      <div class="dash-mid">{{ dashTotals.km }}</div>
                    </div>
                    <div class="dash-col">
                      <div class="mini-label">累计耗电(Wh)</div>
                      <div class="dash-mid">{{ dashTotals.wh }}</div>
                    </div>
                    <div class="dash-col">
                      <div class="mini-label">历史电耗(Wh/km)</div>
                      <div class="dash-mid">{{ dashTotals.hist }}</div>
                    </div>
                  </div>
                </div>

                <div class="set-title">保护板参数（只读）</div>
                <div class="jk-card" style="border-radius: 12px">
                  <div class="detail-cols">
                    <div class="detail-col">
                      <div class="drow"><span class="dlabel">单体过压保护(V)</span><span class="dval">{{ fmt(s?.cell_full_voltage) }}</span></div>
                      <div class="drow"><span class="dlabel">单体欠压保护(V)</span><span class="dval">{{ fmt(s?.cell_empty_voltage) }}</span></div>
                      <div class="drow"><span class="dlabel">充电过温(℃)</span><span class="dval">{{ fmt(s?.charge_over_temp) }}</span></div>
                      <div class="drow"><span class="dlabel">放电过温(℃)</span><span class="dval">{{ fmt(s?.discharge_over_temp) }}</span></div>
                    </div>
                    <div class="detail-col">
                      <div class="drow"><span class="dlabel">充电限流(A)</span><span class="dval">{{ fmt(s?.charge_current_limit) }}</span></div>
                      <div class="drow"><span class="dlabel">放电限流(A)</span><span class="dval">{{ fmt(s?.discharge_current_limit) }}</span></div>
                      <div class="drow"><span class="dlabel">循环次数</span><span class="dval">{{ s?.cycle_count ?? '--' }}次</span></div>
                      <div class="drow"><span class="dlabel">健康度 SOH</span><span class="dval">{{ s?.soh ? s.soh + '%' : '--' }}</span></div>
                    </div>
                  </div>
                </div>

                <div class="jk-card" style="border-radius: 12px">
                  <div style="font-size: 14px; font-weight: 600; margin-bottom: 4px">说明</div>
                  <div class="mini-label">
                    续航 = 剩余电量能量 ÷ 近期平均电耗（EV 通行算法）。刚开始使用时按参考值 20 Wh/km 估算，骑行积累数据后自动修正为您的真实电耗。所有数值为估算，仅供参考。保护板参数修改需设备密码，本应用不提供。
                  </div>
                </div>
              </template>
            </div>

            <!-- 底部导航（图标与客户端 JkTabs 一致） -->
            <div class="tabbar" :style="{ background: c.tabbar, borderTopColor: c.tabline }">
              <div
                v-for="(t, i) in tabDefs" :key="t.label"
                class="tab-item"
                @click="onTab(i)"
              >
                <div class="tab-indicator" :style="{ background: tab === i ? c.green : 'transparent' }" />
                <span class="tab-icon" :style="{ color: tab === i ? c.green : c.gray }" v-html="t.icon" />
              </div>
            </div>
          </div>
        </div>

        <!-- ============ 手机壳之外：管理台专属（不在手机 1:1 范围内） ============ -->
        <div class="side-panels">
          <el-card shadow="never" class="side-card">
            <template #header><span class="title">远程控制（管理台专属）</span></template>
            <div class="mos-row">
              <div class="mos-item">
                <span class="dot" :class="s?.charge_mos_on ? 'g' : 'gy'" />
                <span>充电 MOS：{{ s?.charge_mos_on ? '开启' : '关闭' }}</span>
              </div>
              <div class="mos-item">
                <span class="dot" :class="s?.discharge_mos_on ? 'g' : 'gy'" />
                <span>放电 MOS：{{ s?.discharge_mos_on ? '开启' : '关闭' }}</span>
              </div>
              <div class="mos-item">
                <span class="dot" :class="s?.balance_on ? 'g' : 'gy'" />
                <span>主动均衡：{{ s?.balance_on ? '开启' : '关闭' }}</span>
              </div>
            </div>
            <div class="mos-actions">
              <el-button size="small" :disabled="!connected" @click="toggleMos('charge', !s?.charge_mos_on)">
                {{ s?.charge_mos_on ? '关充电' : '开充电' }}
              </el-button>
              <el-button size="small" :disabled="!connected" @click="toggleMos('discharge', !s?.discharge_mos_on)">
                {{ s?.discharge_mos_on ? '关放电' : '开放电' }}
              </el-button>
              <el-button size="small" type="warning" :disabled="!connected" @click="toggleBalance(!s?.balance_on)">
                {{ s?.balance_on ? '关均衡' : '开均衡' }}
              </el-button>
            </div>
            <div class="mini-label" style="margin-top: 8px">
              控制命令经设备端蓝牙下发保护板；手机端极空页按官方 UI 不含控制入口，故此面板保留在手机壳外。
            </div>
          </el-card>

          <el-card shadow="never" class="side-card">
            <template #header><span class="title">保护 / 告警状态</span></template>
            <div v-if="!protectionEntries.length" class="empty-mini">暂无保护状态数据</div>
            <div v-else class="protect-grid">
              <div v-for="(e, i) in protectionEntries" :key="i" class="protect-item">
                <span class="dot" :class="e.triggered ? 'r' : 'g'" />
                <span class="protect-name">{{ e.name }}</span>
                <span :style="{ color: e.triggered ? c.red : c.green, fontSize: '13px', fontWeight: 600 }">
                  {{ e.triggered ? '触发' : '正常' }}
                </span>
              </div>
            </div>
          </el-card>

          <el-card shadow="never" class="side-card">
            <template #header><span class="title">设备信息</span></template>
            <div class="info-grid">
              <div class="drow"><span class="dlabel">型号</span><span class="dval">{{ s?.model || '--' }}</span></div>
              <div class="drow"><span class="dlabel">硬件版本</span><span class="dval">{{ s?.hardware_version || '--' }}</span></div>
              <div class="drow"><span class="dlabel">固件版本</span><span class="dval">{{ s?.software_version || '--' }}</span></div>
              <div class="drow"><span class="dlabel">蓝牙地址</span><span class="dval">{{ s?.device_address || '--' }}</span></div>
              <div class="drow"><span class="dlabel">充电次数</span><span class="dval">{{ s?.charge_count ?? '--' }}</span></div>
              <div class="drow"><span class="dlabel">状态更新</span><span class="dval">{{ updatedAtText }}</span></div>
            </div>
          </el-card>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { getJikongState, getJikongOdometer, jikongControl,
  type JikongStatus, type JkOdoRecord, type JkOdoSummary,
} from '@/api/jikong'
import { listDevices } from '@/api/devices'
import { useTargetDeviceStore } from '@/stores/targetDevice'

// ==================== 主题（与客户端 JkColors 1:1） ====================
/** 深色（默认，官方经典观感）/ 浅色两套变量，色值取自 JikongPages.kt DarkColors/LightColors。 */
const THEMES = {
  dark: {
    bg: '#000000', card: '#26282B', text: '#E8EAED', gray: '#8A919F', divider: '#33363A',
    green: '#00E050', cyan: '#00B8D4', red: '#E5484D', blue: '#4D8BF5',
    track: '#3A3D40', tabbar: '#1A1C1F', tabline: '#222222',
    g1: '#00E050', g2: '#00C8A0', g3: '#00A8E8',
    numA: '#C8CED6', numB: '#6B727C', onfill: '#06210E',
    badge: '#1F3A66', badgeText: '#FFFFFF', amber: '#FFD60A',
  },
  light: {
    bg: '#EEF1F5', card: '#FFFFFF', text: '#17191D', gray: '#5D6672', divider: '#DFE3E8',
    green: '#00A344', cyan: '#0097B8', red: '#D93036', blue: '#2F6FD8',
    track: '#D7DBE1', tabbar: '#FFFFFF', tabline: '#E3E6EA',
    g1: '#00B050', g2: '#00A8A0', g3: '#0A84D0',
    numA: '#2A2F36', numB: '#9AA2AD', onfill: '#FFFFFF',
    badge: '#D6E4FF', badgeText: '#1A3C8C', amber: '#B45309',
  },
} as const

const themeLight = ref(false)
const c = computed(() => (themeLight.value ? THEMES.light : THEMES.dark))

// ==================== 底部导航图标（assets/icons/*.svg → currentColor 单色） ====================
const svgWrap = (inner: string, vb = '0 0 1024 1024') =>
  `<svg viewBox="${vb}" width="24" height="24" fill="currentColor">${inner}</svg>`

const tabDefs = [
  { label: '首页', icon: svgWrap('<path d="M568 736v168H456V736h112m0-56H456c-30.9 0-56 25.1-56 56v224h224V736c0-30.9-25.1-56-56-56z"/><path d="M512 120l1.7 1.8 352 361.9H792V904H232V483.7h-73.7L512 120c-0.1 0 0 0 0 0m0-56c-15.4 0-30.7 6.3-41.9 18.8l-399 410.3c-7.1 8-9.3 19.7-4.6 30.1s14.8 16.5 25.5 16.5h84V904c0 30.9 25.1 56 56 56h560c30.9 0 56-25.1 56-56V539.7h84c10.7 0 20.9-6.2 25.5-16.5 4.7-10.4 2.5-22.1-4.6-30.1l-399-410.3C542.7 70.3 527.4 64 512 64z"/>') },
  { label: '实时', icon: svgWrap('<path d="M886.8 78.8H135.2c-54.2 0-98.4 44.2-98.4 98.4v565.4c0 54.2 44.2 98.4 98.4 98.4h751.6c54.2 0 98.4-44.2 98.4-98.4V177.2c0-54.2-44-98.4-98.4-98.4z m-751.6 60h751.6c21.2 0 38.4 17.2 38.4 38.4V438h-78.6L779 345.4a29.76 29.76 0 0 0-24.2-12.4h-0.4c-9.8 0.2-18.8 5-24.2 13L616 512.8 576.8 452c-5.4-8.6-14.8-13.8-25-13.8h-0.2c-10 0-19.4 5-25 13.4L466 543.2 360.4 319.6c-4.6-9.8-14.2-16.4-25.2-17.2-10.8-0.8-21.2 4.6-27.2 13.8l-93.6 145.6H96.8V177.2c0-21.2 17.2-38.4 38.4-38.4zM886.8 781H135.2c-21.2 0-38.4-17.2-38.4-38.4V521.8H231c10.2 0 19.8-5.2 25.2-13.8l73.2-113.8L434.8 617c4.6 9.8 14.2 16.4 25 17.2h2.2c10 0 19.4-5 25-13.4l64.4-97.4 38.4 60.2c5.4 8.4 14.8 13.6 24.8 13.8h0.4c9.8 0 19.2-4.8 24.8-13l116-169.2 51.6 70.6c5.6 7.8 14.6 12.4 24.2 12.4h93.8v244.6c-0.2 21-17.4 38.2-38.6 38.2z m-120-565.4c0-16.6 13.4-30 30-30H843c16.6 0 30 13.4 30 30s-13.4 30-30 30h-46.2c-16.6 0-30-13.4-30-30zM170.6 883.2h680.8v60H170.6v-60z"/>') },
  { label: '统计', icon: svgWrap('<path d="M274.03 872.08H49.03a20.68 20.68 0 0 1-20.6-20.6V472a20.68 20.68 0 0 1 20.6-20.61H274.03a20.68 20.68 0 0 1 20.61 20.6v379.34a20.6 20.6 0 0 1-20.6 20.75z m-204.61-41.29h183.35V492.6H69.42z m579.34 41.29H424.27a20.68 20.68 0 0 1-20.6-20.6V325.18a20.68 20.68 0 0 1 20.6-20.6h224.63a20.68 20.68 0 0 1 20.61 20.6v526.14a20.53 20.53 0 0 1-20.6 20.75z m-204.03-41.29h183.35V346.08H444.88z m578.46 41.29H798.71a20.68 20.68 0 0 1-20.46-20.75V24.26a20.68 20.68 0 0 1 20.6-20.6h224.64a20.68 20.68 0 0 1 20.6 20.6V851.32a20.53 20.53 0 0 1-20.75 20.75z m-204.02-41.29h183.34V44.94h-183.2z m236.76 189.56H24.26a20.6 20.6 0 0 1 0-41.29h1031.67a20.6 20.6 0 1 1 0 41.29z"/>', '0 0 1080 1024') },
  { label: '设置', icon: svgWrap('<path d="M663.04 108.54l118.78 69.63c-5.63 34.3-13.82 111.62 16.9 165.89 31.74 55.81 105.47 88.06 137.21 99.33 3.07 19.97 6.14 48.64 6.14 69.12 0 18.94-3.07 46.59-6.14 69.12-32.26 11.78-104.96 43.52-137.22 99.84-31.23 54.78-23.04 132.1-16.9 165.89l-118.78 69.63c-28.16-23.04-89.09-67.58-150.53-67.58-61.95 0-123.9 45.06-151.04 67.58L242.18 847.87c5.63-34.3 13.82-111.62-16.9-165.89-31.74-55.81-105.47-88.06-137.21-99.33-3.07-19.97-6.14-48.64-6.14-69.12 0-18.94 3.07-46.59 6.14-69.12 32.26-11.78 104.96-43.52 137.22-99.84 31.23-54.78 22.53-131.58 16.9-165.89l118.78-69.63c28.16 23.04 89.09 67.58 150.53 67.58 61.95 0 123.9-45.06 151.55-68.1m-2.05-87.04c-14.85 0-30.2 5.63-40.96 15.87-15.87 14.85-69.63 57.34-108.03 57.34-38.4 0-92.16-42.5-108.03-57.34-10.75-10.24-26.11-15.87-40.96-15.87-7.68 0-15.87 1.54-23.04 4.61L184.83 117.25l-3.07 2.05c-17.92 13.31-27.14 38.91-22.53 61.44 5.12 20.99 14.34 89.6-5.12 123.39-22.02 33.79-85.5 59.4-105.98 66.05-20.48 6.66-38.4 26.62-41.98 48.13-.52 2.56-9.22 55.8-9.22 94.72s8.7 92.67 9.22 94.72c3.58 21.5 21.5 41.98 41.98 48.13 20.48 6.65 83.97 32.77 102.91 66.05 18.94 33.79 9.73 101.89 5.12 123.39-5.12 22.02 4.61 47.62 22.53 61.44l155.13 90.11 3.07 1.54c7.17 3.07 14.85 4.61 23.04 4.61 14.85 0 30.21-5.63 40.96-15.87 15.87-14.85 69.63-57.34 108.03-57.34 38.4 0 92.16 42.5 108.03 57.34 10.75 9.73 26.11 15.36 40.96 15.36 7.68 0 15.87-1.54 23.04-4.61l155.14-90.62 3.07-2.05c17.92-13.31 27.14-39.42 22.53-61.44-5.12-20.99-14.34-89.6 5.12-123.39 18.94-33.79 82.43-59.9 102.91-66.05 20.48-6.65 38.4-26.62 41.98-48.13.51-2.56 9.22-55.8 9.22-94.72s-8.7-92.67-9.22-94.72c-3.58-21.5-21.5-41.98-41.98-48.13-20.48-6.65-83.97-32.77-102.91-66.05-18.94-33.79-9.73-101.89-5.12-123.39 5.12-22.02-4.61-47.62-22.53-61.44L687.1 27.65l-3.07-1.54c-7.17-3.07-15.36-4.6-23.04-4.6z"/><path d="M512 369.66c78.85 0 143.36 64.51 143.36 143.36S590.85 656.38 512 656.38s-143.36-64.51-143.36-143.36S433.15 369.66 512 369.66m0-81.92c-124.42 0-225.28 100.86-225.28 225.28S387.58 738.3 512 738.3s225.28-100.86 225.28-225.28S636.42 287.74 512 287.74z"/>') },
]

// ==================== 数据加载 ====================
// 目标设备接全局 store（2026-11-06 用户需求）：与命令下发/自动化调试台一致——
// 有存储的自动选中、切换后写入 store，跨页面保持、刷新不丢。
const targetStore = useTargetDeviceStore()
const selectedDevice = computed({
  get: () => targetStore.deviceId,
  set: (v: string) => targetStore.set(v),
})
const s = ref<JikongStatus | null>(null)
const updatedAt = ref<number | null>(null)
const deviceNameMap = ref<Record<string, string>>({})
const autoRefresh = ref(true)
const tab = ref(0)
let timer: number | undefined

const deviceOptions = computed(() =>
  Object.entries(deviceNameMap.value).map(([id, name]) => ({ value: id, label: name || id })),
)

const connected = computed(() => !!(s.value && s.value.connected))
const hasData = computed(() => connected.value && (s.value!.cell_voltages?.length ?? 0) > 0)
const powerNow = computed(() => (s.value?.total_voltage ?? 0) * (s.value?.current ?? 0))
const charging = computed(() => (s.value?.current ?? 0) > 0.1)
const discharging = computed(() => (s.value?.current ?? 0) < -0.1)
const soc = computed(() => Math.max(0, Math.min(100, s.value?.soc ?? 0)))
const dashEnabled = computed(() => !!s.value?.app?.dash_enabled)
const dashMirror = computed({
  get: () => dashEnabled.value,
  set: () => { /* 只读镜像（手机端开关） */ },
})

const protectionEntries = computed(() => {
  const p = s.value?.protection_states
  if (!p) return []
  return Object.entries(p).map(([name, triggered]) => ({ name, triggered: !!triggered }))
})

// ---------- 格式化（与客户端 fmt 一致：≥100 一位小数，其余两位） ----------
function fmt(v: number | undefined | null): string {
  if (v === undefined || v === null) return '--'
  return Math.abs(v) >= 100 ? v.toFixed(1) : v.toFixed(2)
}
const n1 = (v?: number) => (v == null ? '--' : v.toFixed(1))
const n2 = (v?: number) => (v == null ? '--' : v.toFixed(2))
const gt0 = (v?: number) => v != null && v > 0

function fmtDuration(seconds: number): string {
  const total = Math.max(0, Math.floor(seconds))
  const h = Math.floor(total / 3600)
  const m = Math.floor((total % 3600) / 60)
  const sec = total % 60
  const mm = String(m).padStart(2, '0')
  const ss = String(sec).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${m}:${ss}`
}

const tempAt = (i: number) =>
  s.value?.temperatures && s.value.temperatures.length > i ? fmt(s.value.temperatures[i]) + '℃' : '--'

// ---------- 首页四格 ----------
const quad1 = computed(() => [
  { label: '最高电压(V)', value: fmt(s.value?.max_cell_voltage), color: c.value.blue },
  { label: '最低电压(V)', value: fmt(s.value?.min_cell_voltage), color: c.value.red },
  { label: '最大压差(V)', value: fmt(s.value?.diff_voltage), color: c.value.green },
  { label: '均衡电流(A)', value: fmt(s.value?.balance_current), color: c.value.green },
])
const quad2 = computed(() => [
  { label: '电池容量(Ah)', value: fmt(s.value?.nominal_capacity), color: c.value.green },
  { label: '剩余容量(Ah)', value: fmt(s.value?.remain_capacity), color: c.value.green },
  { label: '最高温度(℃)', value: fmt(s.value?.max_temperature), color: c.value.green },
  { label: '电池类型', value: s.value?.battery_type || '--', color: c.value.green },
])

// ---------- 电流/功率卡（与 CurrentPowerCard 同公式） ----------
const chargeFrac = computed(() => {
  const cur = s.value?.current ?? 0
  const lim = (s.value?.charge_current_limit ?? 0) > 0 ? s.value!.charge_current_limit! : 50
  return charging.value ? Math.min(1, Math.max(0, cur / lim)) : 0
})
const dischFrac = computed(() => {
  const cur = s.value?.current ?? 0
  const lim = (s.value?.discharge_current_limit ?? 0) > 0 ? s.value!.discharge_current_limit! : 100
  return discharging.value ? Math.min(1, Math.max(0, -cur / lim)) : 0
})
/** 剩余时间（与 localRemainTime 同公式：电流过小→0） */
const remainTimeText = computed(() => {
  const st = s.value
  if (!st) return '--'
  const cur = st.current ?? 0
  if (Math.abs(cur) < 0.05) return '--'
  const ah = cur > 0
    ? Math.max(0, (st.nominal_capacity ?? 0) - (st.remain_capacity ?? 0))
    : Math.max(0, st.remain_capacity ?? 0)
  if (ah <= 0) return '--'
  const sec = Math.floor((ah / Math.abs(cur)) * 3600)
  const h = Math.floor(sec / 3600)
  const m = Math.floor((sec % 3600) / 60)
  const ss = sec % 60
  const p = (x: number) => String(x).padStart(2, '0')
  return (cur > 0 ? '满 ' : '空 ') + `${p(h)}:${p(m)}:${p(ss)}`
})

const updatedAtText = computed(() => {
  if (!updatedAt.value) return '--'
  const d = new Date(updatedAt.value * 1000)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}:${String(d.getSeconds()).padStart(2, '0')}`
})

// ==================== 仪表盘 SVG 几何（与 JkGauge 同参数：cx/cy=160 R=126 sw=14） ====================
const CX = 160, CY = 160, R = 126, ROUT = 113
const polar = (r: number, deg: number) => {
  const rad = (deg * Math.PI) / 180
  return { x: CX + r * Math.cos(rad), y: CY + r * Math.sin(rad) }
}
const arcPath = (sweepDeg: number) => {
  const a0 = 120, a1 = 120 + sweepDeg
  const p0 = polar(R, a0), p1 = polar(R, a1)
  return `M ${p0.x.toFixed(2)} ${p0.y.toFixed(2)} A ${R} ${R} 0 ${sweepDeg > 180 ? 1 : 0} 1 ${p1.x.toFixed(2)} ${p1.y.toFixed(2)}`
}
const gaugeTrackPath = arcPath(300)
const gaugeSweep = computed(() => 300 * (soc.value / 100))
const gaugeProgressPath = computed(() => arcPath(gaugeSweep.value))

const gaugeTicks = computed(() => {
  const out: { i: number; x1: number; y1: number; x2: number; y2: number; w: number }[] = []
  for (let i = 0; i <= 100; i++) {
    const deg = 120 + 3 * i
    const len = i % 10 === 0 ? 16 : i % 5 === 0 ? 12 : 8
    const w = i % 10 === 0 ? 2.2 : i % 5 === 0 ? 1.7 : 1.1
    const o = polar(ROUT, deg), inP = polar(ROUT - len, deg)
    out.push({ i, x1: o.x, y1: o.y, x2: inP.x, y2: inP.y, w })
  }
  return out
})
const gaugeNums = computed(() => {
  const out: { i: number; x: number; y: number; major: boolean }[] = []
  for (let i = 0; i <= 100; i += 5) {
    const major = i % 10 === 0
    const p = polar(144, 120 + 3 * i)
    out.push({ i, x: p.x, y: p.y + (major ? 3.5 : 3), major })
  }
  return out
})

// ==================== 实时页网格（列优先：左 1-8 / 中 9-16 / 右 17-24） ====================
interface TriCell { i: number; num: string; text: string; color: string }
function triRows(values: number[] | undefined, isVolt: boolean): TriCell[] {
  const st = s.value
  const total = 24
  const vals = values ?? []
  const rows = Math.ceil(total / 3)
  let maxIdx = -1, minIdx = -1
  if (vals.length && isVolt) {
    maxIdx = vals.indexOf(Math.max(...vals))
    minIdx = vals.indexOf(Math.min(...vals))
  }
  const out: TriCell[] = []
  for (let r = 0; r < rows; r++) {
    for (let col = 0; col < 3; col++) {
      const i = col * rows + r
      if (i >= total) continue
      const has = i < vals.length
      const color = !has ? c.value.gray : i === maxIdx ? c.value.red : i === minIdx ? c.value.amber : c.value.text
      out.push({
        i,
        num: String(i + 1).padStart(2, '0'),
        text: has ? vals[i].toFixed(3) : '--',
        color,
      })
    }
  }
  return out
}

// ==================== 统计 Tab（MySQL 台账，只读） ====================
const odo = ref<{ records: JkOdoRecord[]; summary: JkOdoSummary } | null>(null)
const odoLoading = ref(false)

interface OdoView {
  record_id: string
  date: string
  time: string
  odo: number
  dKm: number
  seg: { d_ah: number; avg_volt: number; kwh: number; wh_km: number } | null
  isBase: boolean
  invalid: boolean
  whColor: string
}

/** 倒序台账视图（最新在上、基准垫底）+ 语义着色（与手机统计页一致）。 */
const odoRecordsView = computed<OdoView[]>(() => {
  const list = odo.value?.records ?? []
  const avg = odo.value?.summary.avg_wh_km
  return list.map((r, idx) => {
    const d = new Date(r.ts_ms)
    const p = (x: number) => String(x).padStart(2, '0')
    const isBase = idx === list.length - 1
    const prev = idx < list.length - 1 ? list[idx + 1] : null
    const invalid = !r.seg && !isBase
    let whColor: string = c.value.text
    if (r.seg && avg != null) whColor = r.seg.wh_km > avg ? c.value.red : c.value.green
    else if (invalid) whColor = c.value.gray
    return {
      record_id: r.record_id,
      date: `${p(d.getMonth() + 1)}-${p(d.getDate())}`,
      time: `${p(d.getHours())}:${p(d.getMinutes())}`,
      odo: r.odo_km,
      dKm: r.seg ? r.seg.d_odo_km : prev ? r.odo_km - prev.odo_km : 0,
      seg: r.seg,
      isBase,
      invalid,
      whColor,
    }
  })
})

/** 能耗曲线（X=里程起点偏移，Y=Wh/km；与手机曲线同口径，只取有效段） */
const curvePoints = computed(() => {
  const list = (odo.value?.records ?? []).slice().sort((a, b) => a.ts_ms - b.ts_ms)
  const valid = list.filter((r) => r.seg)
  if (valid.length < 2) return []
  const x0 = valid[0].odo_km
  const x1 = valid[valid.length - 1].odo_km
  const ys = valid.map((r) => r.seg!.wh_km)
  const yMin = Math.min(...ys), yMax = Math.max(...ys)
  const spanY = Math.max(1, yMax - yMin)
  const spanX = Math.max(0.1, x1 - x0)
  return valid.map((r) => ({
    x: 24 + ((r.odo_km - x0) / spanX) * 284,
    y: 128 - ((r.seg!.wh_km - yMin) / spanY) * 92,
    v: r.seg!.wh_km.toFixed(1),
  }))
})
const curvePolyline = computed(() => curvePoints.value.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' '))
const curveAvg = computed(() => {
  const a = odo.value?.summary.avg_wh_km
  return a != null ? a.toFixed(1) : '--'
})

// ==================== 远程控制（管理台专属，保留自旧版） ====================
async function toggleMos(target: 'charge' | 'discharge', on: boolean) {
  try {
    await jikongControl(selectedDevice.value, {
      action: 'mos',
      charge: target === 'charge',
      discharge: target === 'discharge',
      on,
    })
    ElMessage.success(`${target === 'charge' ? '充电' : '放电'} MOS 指令已下发`)
    setTimeout(loadState, 1500)
  } catch { /* 已提示 */ }
}

async function toggleBalance(on: boolean) {
  try {
    await jikongControl(selectedDevice.value, { action: 'balance', on })
    ElMessage.success(`均衡${on ? '开启' : '关闭'}指令已下发`)
    setTimeout(loadState, 1500)
  } catch { /* 已提示 */ }
}

// ==================== 加载与轮询 ====================
async function loadState() {
  if (!selectedDevice.value) { s.value = null; return }
  try {
    const res = await getJikongState(selectedDevice.value)
    s.value = res.status
    updatedAt.value = res.updated_at
  } catch { /* 错误已由拦截器提示 */ }
}

async function loadOdo() {
  if (!selectedDevice.value) return
  odoLoading.value = true
  try {
    const res = await getJikongOdometer(selectedDevice.value)
    odo.value = { records: res.records, summary: res.summary }
  } catch {
    odo.value = null
  } finally {
    odoLoading.value = false
  }
}

function onTab(i: number) {
  tab.value = i
  if (i === 2 && !odo.value) loadOdo()
}

function refreshAll() {
  loadState()
  if (tab.value === 2) loadOdo()
}

function onDeviceChange() {
  odo.value = null
  tab.value = 0
  loadState()
}

async function loadDeviceNames() {
  const devs = await listDevices()
  deviceNameMap.value = Object.fromEntries(devs.map((d) => [d.device_id, d.device_name || d.device_id]))
  // 无记忆（store 空）才回退选第一台；有记忆时 store 值即选中项（即使设备此刻不在线也保持）
  if (!selectedDevice.value && devs.length > 0) {
    selectedDevice.value = devs[0].device_id
    loadState()
  }
}

onMounted(() => {
  loadDeviceNames()
  timer = window.setInterval(() => {
    if (autoRefresh.value && selectedDevice.value) loadState()
  }, 2000)
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})

// 仪表盘中央胶囊描边样式
const pillOutline = computed(() => ({ border: `1px solid ${c.value.green}`, color: c.value.green }))

// 设置页累计统计（dash 快照，未上报显示 --）
const dashTotals = computed(() => {
  const d = s.value?.dash
  return {
    km: d && d.total_km != null && d.total_km > 0 ? n2(d.total_km) : '--',
    wh: d && d.total_wh != null && d.total_wh > 0 ? Math.round(d.total_wh).toString() : '--',
    hist: d && d.hist_avg_wh_km != null && d.hist_avg_wh_km > 0 ? n1(d.hist_avg_wh_km) : '--',
  }
})
</script>

<style scoped>
/* ==================== 工具栏与舞台 ==================== */
.toolbar-card { margin-bottom: 16px; }
.toolbar { display: flex; justify-content: space-between; align-items: center; }
.tools { display: flex; gap: 8px; align-items: center; }
.title { font-weight: 600; color: #303133; }
.title-sub { font-size: 12px; font-weight: 400; color: #909399; }

.stage {
  display: flex;
  gap: 24px;
  justify-content: center;
  align-items: flex-start;
  flex-wrap: wrap;
}

/* ==================== 手机壳 ==================== */
.phone {
  width: 390px;
  height: 844px;
  background: v-bind('c.bg');
  border-radius: 36px;
  border: 1px solid v-bind('c.divider');
  box-shadow: 0 18px 50px rgba(0, 0, 0, 0.35);
  overflow: hidden;
  display: flex;
  flex-direction: column;
  flex-shrink: 0;
}
.screen { flex: 1; display: flex; flex-direction: column; min-height: 0; }
.content {
  flex: 1;
  overflow-y: auto;
  padding: 10px 14px 10px;
  scrollbar-width: none;
}
.content::-webkit-scrollbar { width: 0; }

/* 通用小元素 */
.dot { display: inline-block; width: 8px; height: 8px; border-radius: 50%; flex-shrink: 0; }
.dot.g { background: v-bind('c.green'); }
.dot.r { background: v-bind('c.red'); }
.dot.gy { background: v-bind('c.gray'); }
.g-text { color: v-bind('c.green'); }
.r-text { color: v-bind('c.red'); }
.mini-label { font-size: 11px; color: v-bind('c.gray'); }
.vdiv { width: 1px; background: v-bind('c.divider'); }
.hdiv { height: 1px; background: v-bind('c.divider'); margin: 6px 0; }
.jk-card { background: v-bind('c.card'); border-radius: 8px; }
.empty-mini { padding: 24px 0; text-align: center; color: v-bind('c.gray'); font-size: 13px; }

/* 连接栏 */
.jk-connbar {
  display: flex; align-items: center; gap: 6px;
  background: v-bind('c.card'); border-radius: 8px;
  padding: 4px 12px; font-size: 13px;
}
.conn-text { flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.conn-text.g-text { color: v-bind('c.green'); }
.conn-text.gy { color: v-bind('c.gray'); }
.theme-btn { color: #1a6ee8; padding: 4px 6px; cursor: pointer; }

/* 状态行 */
.jk-statusrow {
  display: flex; align-items: center;
  background: v-bind('c.card'); border-radius: 8px;
  padding: 9px 0; margin-top: 8px;
}
.jk-statuscell {
  flex: 1; display: flex; align-items: center; justify-content: center;
  gap: 5px; font-size: 14px; color: v-bind('c.text');
}

/* 仪表盘 */
.gauge-wrap { position: relative; width: 88.5%; margin: 2px auto 0; }
.gauge-svg { width: 100%; display: block; }
.gauge-center {
  position: absolute; inset: 0;
  display: flex; flex-direction: column; align-items: center; justify-content: center;
}
.soc-num {
  font-size: 58px; font-weight: 700; letter-spacing: -1px; line-height: 1;
}
.pill-row { display: flex; gap: 8px; margin-top: 8px; }
.pill-outline {
  font-size: 15px; padding: 5px 12px; border-radius: 8px;
}
.pill-fill {
  margin-top: 9px; font-size: 15px; font-weight: 700;
  padding: 6px 13px; border-radius: 8px;
}

/* 健康横幅 */
.jk-health {
  display: flex; align-items: center; gap: 8px;
  background: v-bind('c.card'); border-radius: 8px;
  padding: 7px 14px; margin-top: 6px; font-size: 14px; color: v-bind('c.text');
}
.health-check {
  width: 18px; height: 18px; border-radius: 4px;
  display: inline-flex; align-items: center; justify-content: center; flex-shrink: 0;
}

/* 时速卡 */
.dash-card { padding: 10px 14px; margin-top: 6px; }
.dash-row1 { display: flex; }
.dash-row1 .dash-col { flex: 1; }
.dash-big { font-size: 30px; font-weight: 700; line-height: 1.15; }
.dash-row2 { display: flex; }
.dash-row2 .dash-col, .dash-row3 { flex: 1; }
.dash-mid { font-size: 15px; font-weight: 600; color: v-bind('c.text'); }
.dash-row3 { display: flex; align-items: center; justify-content: space-between; margin-top: 4px; }
.dash-warn { font-size: 11px; margin-top: 4px; }

/* 四格 */
.jk-quad {
  display: flex; align-items: center;
  background: v-bind('c.card'); border-radius: 8px;
  padding: 11px 0; margin-top: 6px;
}
.quad-cell { flex: 1; text-align: center; }
.quad-val { font-size: 17px; font-weight: 700; }
.quad-cell .mini-label { margin-top: 5px; }

/* 电流功率卡 */
.cp-card { padding: 12px 14px 8px; margin-top: 8px; }
.cp-row { display: flex; align-items: baseline; }
.cp-label { font-size: 13px; color: v-bind('c.gray'); }
.cp-val { font-size: 16px; font-weight: 700; margin-left: 6px; }
.cp-ends {
  display: flex; justify-content: space-between; align-items: center;
  margin-top: 10px;
}
.cp-zero { font-size: 12px; font-weight: 700; color: v-bind('c.text'); }
.cp-bar { position: relative; height: 7px; margin-top: 8px; }
.cp-bar-track { position: absolute; inset: 0; border-radius: 3.5px; }
.cp-half { position: absolute; top: 0; height: 100%; width: 50%; overflow: hidden; }
.cp-half-l { left: 0; display: flex; justify-content: flex-end; }
.cp-half-r { right: 0; }
.cp-fill-charge { height: 100%; border-radius: 3.5px 0 0 3.5px; transition: width 0.3s; }
.cp-fill-discharge { height: 100%; background: v-bind('c.red'); border-radius: 0 3.5px 3.5px 0; transition: width 0.3s; }
.cp-center {
  position: absolute; left: 50%; top: 50%; transform: translate(-50%, -50%);
  width: 4px; height: 17px; border-radius: 2px;
}
.cp-status { display: flex; align-items: center; margin-top: 8px; }
.cp-state { font-size: 13px; font-weight: 600; margin-left: 2px; }

/* 离线占位 */
.offline {
  text-align: center; padding-top: 120px; color: v-bind('c.gray');
}
.offline-icon { font-size: 44px; }
.offline-text { font-size: 16px; margin-top: 12px; color: v-bind('c.text'); }
.offline-sub { font-size: 12px; margin-top: 6px; padding: 0 40px; line-height: 1.6; }

/* 实时页 */
.jk-section {
  display: flex; align-items: center; gap: 8px; margin: 10px 0 6px;
}
.sec-dot { width: 8px; height: 8px; border-radius: 50%; background: #00e050; }
.sec-title { font-size: 15px; font-weight: 700; color: #00e050; }
.detail-cols { display: flex; padding: 8px 14px; }
.detail-col { flex: 1; }
.detail-col + .detail-col { margin-left: 24px; }
.drow {
  display: flex; align-items: center; padding: 3px 0; font-size: 13px;
}
.dlabel { flex: 1; color: v-bind('c.gray'); }
.dval { font-weight: 600; color: v-bind('c.text'); }
.tri-grid {
  display: flex; flex-wrap: wrap; border-radius: 10px; padding: 8px 12px;
}
.tri-cell {
  width: 33.33%; display: flex; align-items: center; padding: 4px 0;
}
.tri-badge {
  min-width: 24px; text-align: center; border-radius: 8px;
  padding: 1px 4px; font-size: 12px; font-weight: 600;
}
.tri-val { font-size: 14px; font-weight: 600; margin-left: 7px; }

/* 统计页 */
.stats-summary { display: flex; gap: 8px; margin-bottom: 10px; }
.sum-card { flex: 1; border-radius: 8px; padding: 10px 8px; text-align: center; }
.sum-label { font-size: 11px; color: v-bind('c.gray'); }
.sum-val { font-size: 20px; font-weight: 700; color: v-bind('c.text'); margin-top: 4px; }
.stats-head { display: flex; justify-content: space-between; align-items: baseline; color: v-bind('c.text'); font-size: 14px; }
.curve-svg { width: 100%; margin-top: 6px; }
.rec-list { height: 262px; overflow-y: auto; scrollbar-width: none; }
.rec-list::-webkit-scrollbar { width: 0; }
.rec-row {
  display: flex; align-items: center; gap: 8px;
  padding: 7px 14px; border-top: 1px solid v-bind('c.divider');
}
.rec-date { width: 58px; font-size: 12px; color: v-bind('c.gray'); line-height: 1.4; flex-shrink: 0; }
.rec-base {
  display: inline-block; margin-left: 4px; padding: 0 3px;
  background: v-bind('c.badge'); color: v-bind('c.badgeText');
  border-radius: 3px; font-size: 10px;
}
.rec-odo { width: 92px; font-size: 15px; font-weight: 700; color: v-bind('c.text'); }
.rec-odo small { display: block; font-size: 10px; font-weight: 400; color: v-bind('c.gray'); }
.rec-delta { flex: 1; font-size: 11px; color: v-bind('c.gray'); line-height: 1.5; }
.rec-wh { width: 58px; text-align: right; font-size: 16px; font-weight: 700; }
.rec-wh small { font-size: 9px; font-weight: 400; margin-left: 1px; }
.stats-note { font-size: 11px; color: v-bind('c.gray'); margin-top: 8px; line-height: 1.6; }

/* 设置页 */
.set-title { font-size: 15px; font-weight: 700; color: #00e050; margin: 14px 0 8px; }
.set-row { display: flex; align-items: center; padding: 14px; }

/* 底部导航 */
.tabbar {
  display: flex; border-top: 1px solid; flex-shrink: 0;
}
.tab-item {
  flex: 1; display: flex; flex-direction: column; align-items: center;
  padding: 3px 0 6px; cursor: pointer;
}
.tab-indicator { width: 18px; height: 2px; border-radius: 1px; margin-bottom: 1px; }
.tab-icon { display: flex; }

/* ==================== 手机壳外：管理台专属面板 ==================== */
.side-panels { flex: 1; min-width: 320px; max-width: 560px; display: flex; flex-direction: column; gap: 16px; }
.side-card :deep(.el-card__header) { padding: 12px 16px; }
.mos-row { display: flex; gap: 24px; flex-wrap: wrap; }
.mos-item { display: flex; align-items: center; gap: 8px; font-size: 13px; color: #606266; }
.mos-actions { margin-top: 12px; display: flex; gap: 8px; }
.protect-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 8px; }
.protect-item {
  display: flex; align-items: center; gap: 6px; padding: 8px 10px;
  border-radius: 6px; background: #f5f7fa; font-size: 13px;
}
.protect-name { flex: 1; color: inherit; }
.info-grid .drow { padding: 4px 0; }
.info-grid .dlabel { color: #909399; }
.info-grid .dval { color: #303133; }
</style>
