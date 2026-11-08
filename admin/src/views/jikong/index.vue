<template>
  <div>
    <!-- 顶部工具栏（手机壳之外，管理台功能） -->
    <el-card shadow="never" class="toolbar-card">
      <div class="toolbar">
        <span class="title">极空保护板 <span class="title-sub">（手机端 1:1 复刻 · 数据实时同步自设备）</span></span>
        <div class="tools">
          <el-switch v-model="autoRefresh" active-text="自动刷新" size="small" style="margin-right: 8px" />
          <el-select
            v-model="refreshSec"
            size="small"
            :disabled="!autoRefresh"
            style="width: 104px; margin-right: 8px"
            title="自动刷新频率"
          >
            <el-option v-for="o in refreshOptions" :key="o" :label="o + ' 秒'" :value="o" />
          </el-select>
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
                    <span class="theme-btn" @click="onThemeToggle">{{ themeLight ? '浅色' : '深色' }}</span>
                  </div>

                  <!-- v0.2.101：演示模式横幅（与手机 v0.2.98 同款；后台只读，刷新在手机端操作） -->
                  <div v-if="demoMode" class="jk-demobanner">
                    <span class="dot" :style="{ background: c.amber }" />
                    <span class="demo-text">演示模式 · 模拟数据（切离本页或后台自动退出）</span>
                    <span class="demo-refresh" style="cursor: default">刷新模拟数据（手机端）</span>
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

                  <!-- 中央仪表盘（300°弧 + 0~100 刻度带 + 外围数字 + SOC + 三胶囊）
                       v0.2.102：双击=远程触发手机播报（jikong_speak，话术与手机双击一致） -->
                  <div class="gauge-wrap" title="双击：手机播报当前电量" @dblclick="onRemoteSpeak">
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

                  <!-- 时速仪表盘卡片（开关关闭时与手机一致不渲染）；v0.2.101 行1 三列对齐手机 -->
                  <div v-if="dashEnabled && s.dash" class="jk-card dash-card">
                    <div class="dash-row1">
                      <div class="dash-col">
                        <div class="mini-label">时速(km/h)</div>
                        <div class="dash-big" :style="{ color: c.green }">{{ s.dash.gps_fresh ? n1(s.dash.speed_kmh) : '--' }}</div>
                      </div>
                      <div class="dash-col">
                        <div class="mini-label">平均时速(km/h)</div>
                        <div class="dash-big" :style="{ color: c.text }">{{ gt0(s.dash.avg_speed_kmh) ? n1(s.dash.avg_speed_kmh) : '--' }}</div>
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
                        <div class="mini-label">瞬时({{ consUnit }})</div>
                        <div class="dash-mid">{{ gt0(s.dash.inst_wh_km) ? fmtCons(s.dash.inst_wh_km) : '--' }}</div>
                      </div>
                      <div class="dash-col">
                        <div class="mini-label">平均({{ consUnit }})</div>
                        <div class="dash-mid">{{ gt0(s.dash.avg_wh_km) ? fmtCons(s.dash.avg_wh_km) : '--' }}</div>
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
                        <div class="drow"><span class="dlabel">最大压差</span><span class="dval">{{ fmt3(s.diff_voltage) }}V</span></div>
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

              <!-- ============ Tab 2：统计（只读，录入仍在手机端）；v0.2.101 与手机统计页对齐 ============ -->
              <template v-else-if="tab === 2">
                <!-- 概要五卡（3+2，v0.2.90/91：里程按折扣显示、花费按电费计算） -->
                <div v-if="statsSummary.top.length" class="stats-summary">
                  <div v-for="cell in statsSummary.top" :key="cell.label" class="sum-card" :style="{ background: c.card }">
                    <div class="sum-val-row">
                      <span class="sum-val" :style="{ color: cell.highlight ? c.green : c.text }">{{ cell.value }}</span>
                      <span v-if="cell.value !== '--'" class="sum-unit">{{ cell.unit }}</span>
                    </div>
                    <div class="sum-label">{{ cell.label }}</div>
                    <div v-if="cell.sub" class="sum-sub">{{ cell.sub }}</div>
                  </div>
                </div>
                <div v-if="statsSummary.bottom.length" class="stats-summary">
                  <div v-for="cell in statsSummary.bottom" :key="cell.label" class="sum-card" :style="{ background: c.card }">
                    <div class="sum-val-row">
                      <span class="sum-val" :style="{ color: cell.highlight ? c.green : c.text }">{{ cell.value }}</span>
                      <span v-if="cell.value !== '--'" class="sum-unit">{{ cell.unit }}</span>
                    </div>
                    <div class="sum-label">{{ cell.label }}</div>
                    <div v-if="cell.sub" class="sum-sub">{{ cell.sub }}</div>
                  </div>
                </div>
                <div v-else class="jk-card" style="padding: 14px; text-align: center">
                  <span class="mini-label">暂无记录（在手机端统计页录入，同步后此处可见）</span>
                </div>

                <!-- 阶段统计（v0.2.91/92：一周/一月/一季度/一年/自定义；段按记录时间归属） -->
                <div class="jk-card" style="padding: 12px 14px">
                  <div class="stats-head">
                    <span style="font-weight: 600">阶段统计</span>
                    <span class="mini-label">按记录时间归属</span>
                  </div>
                  <div class="stage-chips">
                    <span
                      v-for="(r, i) in stageRanges" :key="r.label"
                      class="stage-chip"
                      :style="stageIdx === i ? { background: c.green, color: c.onfill, fontWeight: 700 } : { background: c.bg, color: c.gray }"
                      @click="stageIdx = i"
                    >{{ r.label }}</span>
                    <span
                      class="stage-chip"
                      :style="stageIdx === stageRanges.length ? { background: c.green, color: c.onfill, fontWeight: 700 } : { background: c.bg, color: c.gray }"
                      @click="stageIdx = stageRanges.length"
                    >自定义</span>
                  </div>
                  <div v-if="stageIdx === stageRanges.length" class="stage-custom">
                    <el-date-picker
                      v-model="customRange"
                      type="daterange"
                      size="small"
                      range-separator="~"
                      start-placeholder="开始日期"
                      end-placeholder="结束日期"
                      style="width: 100%"
                    />
                  </div>
                  <div class="dash-row2" style="padding: 10px 0 0">
                    <div v-for="cell in stageCards.cards" :key="cell.label" class="dash-col stage-cell">
                      <div class="sum-val-row">
                        <span class="sum-val" :style="{ color: cell.highlight ? c.green : c.text, fontSize: '16px' }">{{ cell.value }}</span>
                        <span v-if="cell.value !== '--'" class="sum-unit">{{ cell.unit }}</span>
                      </div>
                      <div class="sum-label">{{ cell.label }}</div>
                      <div v-if="cell.sub" class="sum-sub">{{ cell.sub }}</div>
                    </div>
                  </div>
                  <div v-if="!stageCards.count" class="empty-mini" style="padding: 8px 0 0">
                    {{ stageIdx === stageRanges.length && !customRange ? '请先选择起止日期' : '该时间段内还没有记录' }}
                  </div>
                </div>

                <div class="jk-card" style="padding: 12px 14px">
                  <div class="stats-head">
                    <span style="font-weight: 600">能耗曲线</span>
                    <span v-if="curveAvgText" class="mini-label" :style="{ color: c.cyan }">平均 {{ curveAvgText }} Wh/km ≈ {{ curveAvgCost }} 元/100km</span>
                    <span v-else class="mini-label">Wh/km · 只读（录入在手机端）</span>
                  </div>
                  <div class="mini-label" style="margin-top: 4px">
                    左轴 Wh/km（绿）· 右轴 元/100km（按电费 {{ priceText }} 元/度换算）· X 轴按折扣里程
                  </div>
                  <svg v-if="curvePoints.length >= 2" viewBox="0 0 330 150" class="curve-svg">
                    <line x1="8" y1="20" x2="322" y2="20" :stroke="c.gray" stroke-width="1" stroke-dasharray="4 4" opacity="0.6" />
                    <polyline :points="curvePolyline" fill="none" :stroke="c.green" stroke-width="2" stroke-linejoin="round" />
                    <circle v-for="(p, i) in curvePoints" :key="i" :cx="p.x" :cy="p.y" r="3" :fill="c.green" />
                    <text v-for="(p, i) in curvePoints" :key="'v' + i" :x="p.x" :y="p.y - 7" text-anchor="middle" :fill="c.text" font-size="9">{{ p.v }}</text>
                  </svg>
                  <div v-else class="empty-mini">有效能耗段不足（需至少两条记录且容量递增）</div>
                </div>

                <div class="jk-card" style="padding: 12px 0 10px">
                  <div class="stats-head" style="padding: 0 14px 8px">
                    <span style="font-weight: 600">记录台账</span>
                    <span v-if="demoMode" class="mini-label" :style="{ color: c.amber }">{{ statsRecords.length }} 条演示 · 与手机演示一致</span>
                    <span v-else class="mini-label">{{ statsRecords.length }} 条 · 新在上 · MySQL（手机已同步部分）</span>
                  </div>
                  <div class="rec-list">
                    <div v-for="rec in odoRecordsView" :key="rec.record_id" class="rec-row">
                      <div class="rec-date">{{ rec.date }}<br>{{ rec.time }}<span v-if="rec.isBase" class="rec-base">基准</span></div>
                      <div class="rec-odo">
                        {{ (rec.odo * discount).toFixed(1) }}
                        <small v-if="discount < 1">原 {{ rec.odo.toFixed(1) }}</small>
                        <small>{{ rec.isBase ? '第一条记录' : `距上次 +${(rec.dKm * discount).toFixed(1)} km` }}</small>
                      </div>
                      <div class="rec-delta">
                        <template v-if="rec.seg">{{ rec.seg.d_ah.toFixed(1) }} Ah · {{ rec.seg.avg_volt.toFixed(1) }} V<br>{{ rec.seg.kwh.toFixed(2) }} kWh</template>
                        <template v-else-if="!rec.isBase && rec.invalid">本段无效（容量回退）</template>
                        <template v-else-if="rec.isBase">第一条记录<br>作为计算起点</template>
                        <template v-else>—</template>
                      </div>
                      <div class="rec-wh" :style="{ color: rec.whColor }">
                        <template v-if="rec.seg">
                          {{ (rec.seg.wh_km / discount).toFixed(1) }}<small>Wh/km</small>
                          <small v-if="discount < 1">原 {{ rec.seg.wh_km.toFixed(1) }}</small>
                          <small class="rec-cost">≈{{ (rec.seg.kwh * price).toFixed(2) }} 元</small>
                        </template>
                        <template v-else>--</template>
                      </div>
                    </div>
                    <div v-if="!odoRecordsView.length" class="empty-mini">暂无记录（在手机端统计页录入）</div>
                  </div>
                </div>
                <div class="stats-note">
                  口径：电耗 = Δ循环容量(Ah) × 平均电压(V) ÷ Δ里程(km)。循环容量由保护板累计（只增不减），充电不影响；容量回退段自动标记无效不进曲线。<span v-if="discount < 1">当前里程折扣 {{ discount.toFixed(1) }}：所有里程按折扣显示（真实里程），电耗/花费按真实里程重算，原值以小字保留。</span>
                </div>
              </template>

              <!-- ============ Tab 3：参数设置（只读镜像，v0.2.101 与手机设置页 v0.2.89/90 结构一致） ============ -->
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

                <div class="set-title">语音播报</div>
                <div class="jk-card" style="border-radius: 12px">
                  <div class="set-row" style="padding-bottom: 6px">
                    <div style="flex: 1">
                      <div style="font-size: 14px; font-weight: 600">连接后播报电量</div>
                      <div class="mini-label" style="margin-top: 2px">
                        本页打开时播报电量：电量变化即播；相同且超播报间隔再播；低于阈值立即提醒并按预警间隔重复提醒。
                      </div>
                    </div>
                    <el-switch :model-value="!!s?.app?.speak_enabled" disabled size="small" style="margin-left: 8px" />
                  </div>
                  <div class="dash-row2" style="padding: 0 14px 12px">
                    <div class="dash-col">
                      <div class="mini-label">播报间隔</div>
                      <div class="dash-mid">{{ s?.app?.speak_interval_min != null ? s.app.speak_interval_min + ' 分钟' : '--' }}</div>
                    </div>
                    <div class="dash-col">
                      <div class="mini-label">低电量阈值</div>
                      <div class="dash-mid">{{ s?.app?.speak_low_soc != null ? s.app.speak_low_soc + ' %' : '--' }}</div>
                    </div>
                    <div class="dash-col">
                      <div class="mini-label">预警提醒间隔</div>
                      <div class="dash-mid">{{ s?.app?.speak_warn_repeat_min != null ? s.app.speak_warn_repeat_min + ' 分钟' : '--' }}</div>
                    </div>
                  </div>
                </div>

                <div class="set-title">单位与花费</div>
                <div class="jk-card" style="border-radius: 12px">
                  <div class="set-row">
                    <div style="flex: 1">
                      <div style="font-size: 13px; color: v-bind('c.text')">电耗单位</div>
                      <div class="mini-label" style="margin-top: 2px">单位切换只影响显示（首页仪表盘瞬时/平均电耗），内部计算一律 Wh/km</div>
                    </div>
                    <span
                      class="mini-label"
                      :style="{
                        background: !unitKwh100 ? c.blue : 'transparent',
                        color: !unitKwh100 ? '#fff' : c.gray,
                        borderRadius: '6px', padding: '4px 10px', fontWeight: 600,
                      }"
                    >Wh/km</span>
                    <span
                      class="mini-label"
                      :style="{
                        background: unitKwh100 ? c.blue : 'transparent',
                        color: unitKwh100 ? '#fff' : c.gray,
                        borderRadius: '6px', padding: '4px 10px', fontWeight: 600, marginLeft: '6px',
                      }"
                    >kWh/100km</span>
                  </div>
                  <div class="set-row" style="padding-top: 0">
                    <div style="flex: 1">
                      <div style="font-size: 13px; color: v-bind('c.text')">电费单价</div>
                      <div class="mini-label" style="margin-top: 2px">用于统计页区间总花费与平均花费（元/100km）</div>
                    </div>
                    <span class="dash-mid">{{ s?.app?.elec_price != null ? s.app.elec_price.toFixed(2) + ' 元/度' : '--' }}</span>
                  </div>
                </div>

                <div class="set-title">里程折扣</div>
                <div class="jk-card" style="border-radius: 12px">
                  <div class="set-row">
                    <div style="flex: 1">
                      <div style="font-size: 13px; color: v-bind('c.text')">三轮车表显里程折扣</div>
                      <div class="mini-label" style="margin-top: 2px">
                        表显里程偏大时按折扣换算真实里程（只影响统计页显示层，台账数据库始终存原始输入）
                      </div>
                    </div>
                    <span class="dash-mid">{{ s?.app?.odo_discount != null ? s.app.odo_discount.toFixed(1) : '--' }}</span>
                  </div>
                </div>

                <div class="set-title">累计统计</div>
                <div class="jk-card" style="border-radius: 12px">
                  <div class="dash-row2" style="padding: 12px 14px">
                    <div class="dash-col">
                      <div class="mini-label">累计里程(km)</div>
                      <div class="dash-mid">{{ dashTotals.km }}</div>
                    </div>
                    <div class="dash-col">
                      <div class="mini-label">累计耗电(Wh)</div>
                      <div class="dash-mid">{{ dashTotals.wh }}</div>
                    </div>
                    <div class="dash-col">
                      <div class="mini-label">生命周期电耗</div>
                      <div class="dash-mid">{{ dashTotals.hist }}</div>
                    </div>
                  </div>
                </div>

                <div class="jk-card" style="border-radius: 12px; padding: 12px 14px">
                  <div style="font-size: 14px; font-weight: 600; margin-bottom: 4px">说明</div>
                  <div class="mini-label">
                    续航 = 剩余电量能量 ÷ 预测电耗（EV 通行算法）。预测电耗优先级（v0.2.90）：本次行程平均 &gt; 生命周期累计平均（装机起累积、随里程越来越准）&gt; 会话 EMA 暖场 &gt; 参考值 20 Wh/km。所有数值为估算，仅供参考。保护板参数修改需设备密码，本应用不提供。
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
                <span class="dot" :class="connected && s?.charge_mos_on ? 'g' : 'gy'" />
                <span>充电 MOS：{{ connected ? (s?.charge_mos_on ? '开启' : '关闭') : '未知' }}</span>
              </div>
              <div class="mos-item">
                <span class="dot" :class="connected && s?.discharge_mos_on ? 'g' : 'gy'" />
                <span>放电 MOS：{{ connected ? (s?.discharge_mos_on ? '开启' : '关闭') : '未知' }}</span>
              </div>
              <div class="mos-item">
                <span class="dot" :class="connected && s?.balance_on ? 'g' : 'gy'" />
                <span>主动均衡：{{ connected ? (s?.balance_on ? '开启' : '关闭') : '未知' }}</span>
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
          </el-card>

          <!-- v0.2.102：语音播报（与手机设置页/小喇叭同源，改动即时下发手机） -->
          <el-card shadow="never" class="side-card">
            <template #header><span class="title">语音播报（同步到手机）</span></template>
            <div class="spk-row">
              <div style="flex: 1">
                <div class="spk-label">小喇叭{{ spkMuted ? '（已静音）' : '' }}</div>
                <div class="mini-label">静音后手机不再自动播报（双击表盘播报不受影响）</div>
              </div>
              <el-switch v-model="spkMuted" size="small" @change="sendSpeakSettings('小喇叭')" />
            </div>
            <div class="spk-row">
              <div style="flex: 1">
                <div class="spk-label">语音播报</div>
                <div class="mini-label">连接后按变化/间隔自动播报电量</div>
              </div>
              <el-switch v-model="spkEnabled" size="small" @change="sendSpeakSettings('语音播报')" />
            </div>
            <div class="spk-row">
              <div class="spk-label" style="width: 96px">播报间隔</div>
              <el-select v-model="spkInterval" size="small" style="flex: 1" @change="sendSpeakSettings('播报间隔')">
                <el-option v-for="m in [1, 5, 10, 15, 30, 60]" :key="m" :label="m + ' 分钟'" :value="m" />
              </el-select>
            </div>
            <div class="spk-row">
              <div class="spk-label" style="width: 96px">低电阈值</div>
              <el-input-number v-model="spkLowSoc" size="small" :min="1" :max="50" style="flex: 1" @change="sendSpeakSettings('低电阈值')" />
            </div>
            <div class="spk-row">
              <div class="spk-label" style="width: 96px">提醒间隔</div>
              <el-select v-model="spkWarnRepeat" size="small" style="flex: 1" @change="sendSpeakSettings('提醒间隔')">
                <el-option v-for="m in [1, 5, 10, 30]" :key="m" :label="m + ' 分钟'" :value="m" />
              </el-select>
            </div>
            <div class="mini-label" style="margin-top: 8px">
              改动即时下发手机（jikong_set_speak），手机端设置页同步生效。
            </div>
          </el-card>

          <el-card shadow="never" class="side-card">
            <template #header><span class="title">保护 / 告警状态</span></template>
            <div v-if="!connected" class="empty-mini">设备未连接保护板，保护状态未知</div>
            <div v-else-if="!protectionEntries.length" class="empty-mini">暂无保护状态数据</div>
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
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { getJikongState, getJikongOdometer, jikongControl,
  type JikongStatus, type JkOdoRecord, type JkOdoSeg, type JkOdoSummary,
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
// v0.2.102：自动刷新频率可设置（2/5/10/30/60 秒，localStorage 记忆）
const refreshOptions = [2, 5, 10, 30, 60]
const refreshSec = ref<number>(Number(localStorage.getItem('jk_refresh_sec')) || 5)
const tab = ref(0)
let timer: number | undefined

function restartTimer() {
  if (timer) clearInterval(timer)
  timer = window.setInterval(() => {
    if (autoRefresh.value && selectedDevice.value) loadState()
  }, refreshSec.value * 1000)
}
watch(refreshSec, (v) => {
  localStorage.setItem('jk_refresh_sec', String(v))
  restartTimer()
})

// ==================== v0.2.102：后台↔手机双向同步（主题/播报/远程播报） ====================
// ★ 位置铁律：必须放在 s / selectedDevice 声明之后——watch 注册即执行一次 getter，
//   若引用未初始化的 const s 会抛 TDZ ReferenceError → 整个组件白屏（2026-11-08 实证踩坑）。
/**
 * 主题双向同步：客户端上报 app.theme（0=自动 1=浅色 2=深色）→ 后台跟随；
 * 后台点击切换 → jikong_set_theme 下发 → 手机持久化+即时生效（总纲：两端一致）。
 */
watch(() => s.value?.app?.theme, (t) => {
  if (t === 1) themeLight.value = true
  else if (t === 2) themeLight.value = false
})
function onThemeToggle() {
  themeLight.value = !themeLight.value
  const mode = themeLight.value ? 1 : 2
  jikongControl(selectedDevice.value, { action: 'set_theme', params: { mode } })
    .then(() => ElMessage.success('主题已同步到手机'))
    .catch(() => ElMessage.warning('主题已本地切换，同步手机失败（设备不在线？）'))
}

/** 双击仪表盘 → 远程触发手机播报（jikong_speak，话术与手机双击一致）。 */
async function onRemoteSpeak() {
  try {
    await jikongControl(selectedDevice.value, { action: 'speak' })
    ElMessage.success('播报指令已下发，手机将播报当前电量')
  } catch { /* 已提示 */ }
}

// ---- 语音播报设置（镜像手机上报值；改动即时全量下发，字段全传客户端按需覆盖） ----
const spkMuted = ref(false)
const spkEnabled = ref(false)
const spkInterval = ref(5)
const spkLowSoc = ref(30)
const spkWarnRepeat = ref(5)
let spkLastSendAt = 0
watch(() => s.value?.app, (app) => {
  if (!app || Date.now() - spkLastSendAt < 6000) return   // 刚下发的回声窗口内不回写
  if (app.speak_muted != null) spkMuted.value = app.speak_muted
  if (app.speak_enabled != null) spkEnabled.value = app.speak_enabled
  if (app.speak_interval_min != null) spkInterval.value = app.speak_interval_min
  if (app.speak_low_soc != null) spkLowSoc.value = app.speak_low_soc
  if (app.speak_warn_repeat_min != null) spkWarnRepeat.value = app.speak_warn_repeat_min
}, { immediate: true })
async function sendSpeakSettings(what: string) {
  spkLastSendAt = Date.now()
  try {
    await jikongControl(selectedDevice.value, {
      action: 'set_speak',
      params: {
        muted: spkMuted.value,
        speak_enabled: spkEnabled.value,
        speak_interval_min: spkInterval.value,
        speak_low_soc: spkLowSoc.value,
        speak_warn_repeat_min: spkWarnRepeat.value,
      },
    })
    ElMessage.success(`${what} 已同步手机`)
  } catch {
    ElMessage.warning('下发失败（设备不在线？）')
  }
}

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

// ---------- v0.2.101：与手机 1:1 对齐所需（演示/单位/折扣/电费） ----------
const demoMode = computed(() => !!s.value?.app?.demo_mode)
const unitKwh100 = computed(() => !!s.value?.app?.unit_kwh100)
const consUnit = computed(() => (unitKwh100.value ? 'kWh/100km' : 'Wh/km'))
/** 与客户端 fmtCons 同公式：kWh/100km=Wh/km÷10 两位小数；Wh/km ≥100 整数否则一位 */
function fmtCons(whkm?: number): string {
  if (whkm == null) return '--'
  if (unitKwh100.value) return (whkm / 10).toFixed(2)
  return whkm >= 100 ? whkm.toFixed(0) : whkm.toFixed(1)
}
/** 里程折扣/电费单价来自手机上报的本地设置（旧版 APK 未上报时折扣=1、单价=0 → 花费 --） */
const discount = computed(() => s.value?.app?.odo_discount ?? 1)
const price = computed(() => s.value?.app?.elec_price ?? 0)
const priceText = computed(() =>
  s.value?.app?.elec_price != null ? s.value.app.elec_price.toFixed(2) : '--',
)

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
/** v0.2.102：压差三位小数（0.008V 级别两位会变 0.01，与手机 fmt3 同口径） */
const fmt3 = (v?: number | null) => (v == null ? '--' : v.toFixed(3))
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
  { label: '最大压差(V)', value: fmt3(s.value?.diff_voltage), color: c.value.green },
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

/**
 * v0.2.102：统计页数据源——演示模式用客户端上报的 demo_stats（与手机演示台账同源，
 * 含演示中新增/删除），否则用 MySQL 台账。演示记录按升序算段（与服务端 _compute_segments
 * 同公式：Δ容量×平均电压÷Δ里程），再转倒序输出（最新在上）。
 */
const statsRecords = computed<JkOdoRecord[]>(() => {
  const demo = s.value?.demo_stats
  if (demoMode.value && demo?.records?.length) {
    const asc = [...demo.records].sort((a, b) => a.ts_ms - b.ts_ms)
    const out: JkOdoRecord[] = []
    for (let i = 0; i < asc.length; i++) {
      const r = asc[i]
      let seg: JkOdoSeg | null = null
      if (i > 0) {
        const p = asc[i - 1]
        const dOdo = Number((r.odo_km - p.odo_km).toFixed(3))
        const dAh = Number((r.cycle_ah - p.cycle_ah).toFixed(3))
        const v0 = p.volt || r.volt
        const v1 = r.volt || p.volt
        if (dOdo > 0 && dAh > 0 && v0 && v1) {
          const avgV = (v0 + v1) / 2
          seg = {
            d_odo_km: dOdo,
            d_ah: dAh,
            avg_volt: Number(avgV.toFixed(2)),
            kwh: Number(((dAh * avgV) / 1000).toFixed(3)),
            wh_km: Number(((dAh * avgV) / dOdo).toFixed(1)),
          }
        }
      }
      out.push({
        record_id: r.ts_ms.toString(),
        ts_ms: r.ts_ms,
        odo_km: r.odo_km,
        cycle_ah: r.cycle_ah,
        volt: r.volt,
        deleted: false,
        seg,
      })
    }
    return out.reverse()
  }
  return odo.value?.records ?? []
})

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
  const list = statsRecords.value
  return list.map((r, idx) => {
    const d = new Date(r.ts_ms)
    const p = (x: number) => String(x).padStart(2, '0')
    const isBase = idx === list.length - 1
    const prev = idx < list.length - 1 ? list[idx + 1] : null
    const invalid = !r.seg && !isBase
    // v0.2.101：与手机台账行同口径——折扣后电耗 ≥38 偏高(amber) / ≤31 偏优(green) / 其余正文色
    let whColor: string = c.value.text
    if (r.seg) {
      const whDisp = r.seg.wh_km / discount.value
      whColor = whDisp >= 38 ? c.value.amber : whDisp <= 31 ? c.value.green : c.value.text
    } else if (invalid) whColor = c.value.gray
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

/** 概要基量（与手机统计页同公式：基准→末条里程、有效段耗电、平均电耗），v0.2.101。 */
const statsBase = computed(() => {
  const list = statsRecords.value
  if (list.length < 2) return null
  const valid = list.filter((r) => r.seg)
  const totalKmRaw = list[list.length - 1].odo_km - list[0].odo_km
  const totalWh = valid.reduce((a, r) => a + r.seg!.kwh * 1000, 0)
  const totalValidKm = valid.reduce((a, r) => a + r.seg!.d_odo_km, 0)
  const avgWh = totalValidKm > 0.5 ? totalWh / totalValidKm : 0
  return { count: list.length, totalKmRaw, totalWh, avgWh }
})

interface SumCellV { label: string; value: string; unit: string; highlight?: boolean; sub?: string }

/** 概要五卡（3+2）：里程按折扣显示、电耗按真实里程重算、花费按电费（v0.2.90/91 口径）。 */
const statsSummary = computed<{ top: SumCellV[]; bottom: SumCellV[] }>(() => {
  const b = statsBase.value
  if (!b) return { top: [], bottom: [] }
  const d = discount.value, pr = price.value
  const avgWhDisp = b.avgWh > 0 ? b.avgWh / d : 0
  const totalCost = b.totalWh / 1000 * pr
  const avgCost100 = avgWhDisp > 0 ? avgWhDisp * pr / 10 : 0
  return {
    top: [
      { label: '记录区间里程', value: b.totalKmRaw > 0 ? (b.totalKmRaw * d).toFixed(1) : '--', unit: 'km',
        sub: d < 1 && b.totalKmRaw > 0 ? `原 ${b.totalKmRaw.toFixed(1)}` : undefined },
      { label: '区间总耗电', value: b.totalWh > 0 ? (b.totalWh / 1000).toFixed(2) : '--', unit: 'kWh' },
      { label: '区间总花费', value: totalCost > 0 ? totalCost.toFixed(2) : '--', unit: '元', highlight: true },
    ],
    bottom: [
      { label: '平均电耗', value: avgWhDisp > 0 ? avgWhDisp.toFixed(1) : '--', unit: 'Wh/km',
        sub: d < 1 && b.avgWh > 0 ? `原 ${b.avgWh.toFixed(1)}` : undefined },
      { label: '平均花费', value: avgCost100 > 0 ? avgCost100.toFixed(2) : '--', unit: '元/100km', highlight: true },
    ],
  }
})

// ---------- 阶段统计（v0.2.91/92：预设窗口 + 自定义日期区间；段按记录时间归属） ----------
const stageRanges = [
  { label: '一周', days: 7 },
  { label: '一月', days: 30 },
  { label: '一季度', days: 90 },
  { label: '一年', days: 365 },
]
const stageIdx = ref(1)   // 默认近一月（与手机一致）
const customRange = ref<[Date, Date] | null>(null)

const startOfLocalDay = (dt: Date) => new Date(dt.getFullYear(), dt.getMonth(), dt.getDate()).getTime()

const stageCards = computed<{ count: number; cards: SumCellV[] }>(() => {
  const list = statsRecords.value
  const d = discount.value, pr = price.value
  const valid = list.filter((r): r is typeof r & { seg: NonNullable<typeof r.seg> } => !!r.seg)
  let segs = valid
  if (stageIdx.value === stageRanges.length) {
    if (!customRange.value) segs = []
    else {
      const start = startOfLocalDay(customRange.value[0])
      const end = startOfLocalDay(customRange.value[1]) + 86_400_000
      segs = valid.filter((r) => r.ts_ms >= start && r.ts_ms < end)
    }
  } else {
    const cutoff = Date.now() - stageRanges[stageIdx.value].days * 86_400_000
    segs = valid.filter((r) => r.ts_ms >= cutoff)
  }
  const kmRaw = segs.reduce((a, r) => a + r.seg.d_odo_km, 0)
  const wh = segs.reduce((a, r) => a + r.seg.kwh * 1000, 0)
  const avgWh = kmRaw > 0.1 ? wh / kmRaw / d : 0
  const cost = wh / 1000 * pr
  const avgCost = avgWh > 0 ? avgWh * pr / 10 : 0
  return {
    count: segs.length,
    cards: [
      { label: '阶段里程', value: kmRaw > 0 ? (kmRaw * d).toFixed(1) : '--', unit: 'km',
        sub: d < 1 && kmRaw > 0 ? `原 ${kmRaw.toFixed(1)}` : undefined },
      { label: '阶段花费', value: cost > 0 ? cost.toFixed(2) : '--', unit: '元', highlight: true },
      { label: '平均花费', value: avgCost > 0 ? avgCost.toFixed(2) : '--', unit: '元/100km' },
    ],
  }
})

/** 能耗曲线（X=折扣里程，Y=折扣后 Wh/km；与手机曲线同口径，只取有效段） */
const curvePoints = computed(() => {
  const list = statsRecords.value.slice().sort((a, b) => a.ts_ms - b.ts_ms)
  const valid = list.filter((r) => r.seg)
  if (valid.length < 2) return []
  const d = discount.value
  const x0 = valid[0].odo_km * d
  const x1 = valid[valid.length - 1].odo_km * d
  const ys = valid.map((r) => r.seg!.wh_km / d)
  const yMin = Math.min(...ys), yMax = Math.max(...ys)
  const spanY = Math.max(1, yMax - yMin)
  const spanX = Math.max(0.1, x1 - x0)
  return valid.map((r) => ({
    x: 24 + ((r.odo_km * d - x0) / spanX) * 284,
    y: 128 - ((r.seg!.wh_km / d - yMin) / spanY) * 92,
    v: (r.seg!.wh_km / d).toFixed(1),
  }))
})
const curvePolyline = computed(() => curvePoints.value.map((p) => `${p.x.toFixed(1)},${p.y.toFixed(1)}`).join(' '))
const curveAvgText = computed(() => {
  const b = statsBase.value
  if (!b || b.avgWh <= 0) return ''
  return (b.avgWh / discount.value).toFixed(1)
})
const curveAvgCost = computed(() => {
  const b = statsBase.value
  if (!b || b.avgWh <= 0) return '--'
  return (b.avgWh / discount.value * price.value / 10).toFixed(2)
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
  } catch {
    // 获取失败（服务端不可达等）：不得保留上一次的旧信息，全部回「--/未知」（用户拍板 2026-11-06）
    s.value = null
    updatedAt.value = null
  }
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
  restartTimer()
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})

// 仪表盘中央胶囊描边样式
const pillOutline = computed(() => ({ border: `1px solid ${c.value.green}`, color: c.value.green }))

// 设置页累计统计（dash 快照；未连接保护板时一律 --，不显示断开前旧值）
const dashTotals = computed(() => {
  if (!connected.value) return { km: '--', wh: '--', hist: '--' }
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
/* 卡片间统一垂直间距（对应手机端卡间 Spacer 12dp）；set-title 自带 14px 上边距优先 */
.content > * + * { margin-top: 12px; }
.content > .set-title { margin-top: 14px; }

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
.detail-cols { display: flex; padding: 12px 14px; }
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

/* 演示横幅（v0.2.101，与手机 v0.2.98 同款） */
.jk-demobanner {
  display: flex; align-items: center; gap: 6px;
  background: v-bind('c.card'); border-radius: 8px;
  padding: 4px 12px; margin-top: 8px;
}
.demo-text { flex: 1; font-size: 11px; color: v-bind('c.amber'); }
.demo-refresh { font-size: 12px; color: v-bind('c.gray'); opacity: 0.7; }

/* 统计页概要五卡（v0.2.101：值+单位同行、原值小字） */
.sum-val-row { display: flex; align-items: baseline; justify-content: center; gap: 2px; }
.sum-unit { font-size: 9px; color: v-bind('c.gray'); }
.sum-sub { font-size: 8px; color: v-bind('c.gray'); margin-top: 1px; }

/* v0.2.102：语音播报卡（后台↔手机同步） */
.spk-row { display: flex; align-items: center; gap: 8px; padding: 7px 0; }
.spk-row + .spk-row { border-top: 1px solid v-bind('c.divider'); }
.spk-label { font-size: 14px; font-weight: 600; color: v-bind('c.text'); }

/* 阶段统计 chips（v0.2.93 手机端样式：无「近」字、禁换行） */
.stage-chips { display: flex; align-items: center; gap: 6px; margin-top: 8px; flex-wrap: nowrap; }
.stage-chip {
  font-size: 12px; border-radius: 14px; padding: 5px 12px;
  cursor: pointer; white-space: nowrap; user-select: none;
}
.stage-custom { margin-top: 8px; }
.stage-cell { text-align: center; }
.stage-cell .sum-val-row, .stage-cell .sum-label, .stage-cell .sum-sub { justify-content: center; }
.rec-cost { display: block; }

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
