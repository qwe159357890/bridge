<template>
  <el-dialog v-model="visible" :title="title" width="720px" destroy-on-close class="dstat-detail">
    <template v-if="record">
      <!-- 通用：命令元信息条 -->
      <div class="dstat-meta">
        <el-tag :type="statusTagType" size="small" effect="dark">{{ statusText }}</el-tag>
        <span>ID {{ record.command_id.slice(0, 8) }}…</span>
        <span>设备 {{ deviceLabel }}</span>
        <span>下发 {{ formatTime(record.created_at) }}</span>
        <span v-if="record.result_at">回执 {{ formatTime(record.result_at) }}</span>
      </div>

      <!-- ① 电量：顶部大字百分比 + 充电/温度/电压/健康度网格 -->
      <div class="dstat-section">
        <div class="dstat-section-title">🔋 电量</div>
        <div v-if="battery" class="battery-row">
          <div class="battery-hero">
            <div class="battery-ring" :style="ringStyle">
              <span class="battery-pct">{{ battery.pct ?? '—' }}</span>
              <span class="battery-unit">%</span>
            </div>
            <div class="battery-side">
              <div class="battery-status">
                <el-tag :type="battery.charging ? 'warning' : 'success'" size="small" effect="dark">
                  {{ battery.status }}
                </el-tag>
                <span v-if="battery.plug" class="battery-plug">{{ battery.plug }}</span>
              </div>
              <div class="battery-note">查询时刻的本地电池快照</div>
            </div>
          </div>
        </div>
        <div v-if="batteryFields.length" class="dstat-kv">
          <div v-for="f in batteryFields" :key="f.label" class="dstat-kv-item">
            <span class="dstat-kv-label">{{ f.label }}</span>
            <span class="dstat-kv-value">{{ f.value }}</span>
          </div>
        </div>
        <div v-if="active" class="dstat-active">
          <span class="dstat-active-label">本 App 活跃时长</span>
          <span class="dstat-active-value">{{ active }}</span>
          <span class="dstat-active-note">（非耗电值，仅活跃度参考）</span>
        </div>
        <div v-else class="dstat-empty">未取得电量信息（设备可能无电池，或回执缺失）。</div>
      </div>

      <!-- ② 流量：四块（本 App 开机累计 / 本 App 本月 / 整机开机累计） -->
      <div class="dstat-section">
        <div class="dstat-section-title">📶 流量</div>
        <div class="traffic-cards">
          <div v-for="t in trafficCards" :key="t.title" class="traffic-card">
            <div class="traffic-card-title">{{ t.title }}</div>
            <div class="traffic-card-body">
              <template v-if="t.rows.length">
                <div v-for="r in t.rows" :key="r.label" class="traffic-row">
                  <span class="traffic-row-label">{{ r.label }}</span>
                  <span class="traffic-row-value">{{ r.value }}</span>
                </div>
              </template>
              <div v-else class="traffic-row-empty">{{ t.fallback }}</div>
            </div>
            <div v-if="t.note" class="traffic-card-note">{{ t.note }}</div>
          </div>
        </div>
      </div>

      <!-- 通用：回执原文 -->
      <div class="dstat-raw">
        <div class="dstat-raw-head">
          <span>回执原文</span>
          <el-button size="small" link type="primary" @click="copyRaw">复制</el-button>
        </div>
        <pre class="dstat-raw-body">{{ rawText }}</pre>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { CommandRecord } from '@/types'
import { formatTime } from '@/utils/format'

/**
 * get_device_stats（电/流量统计）的「查看详情」弹窗（命令下发页 · 命令历史回执列）。
 *
 * 设计要点（沿用 VNT/SSH/UI 同款「按命令分治」约定）：从现有 CommandRecord
 * 的 result_message 解析电量 + 活跃时长 + 四块流量，渲染结构化美观弹窗，
 * 不做通用回执文本框。数据来源仅限现有 CommandRecord，不改客户端回执格式。
 *
 * 回执格式（客户端 DeviceStatsReporter.read 固定）：
 *   【电量】电量：88%（充电中·AC 电源）｜温度：32.5℃｜电压：4.21V｜健康度：良好｜电池：Li-poly
 *           ｜本 App 活跃时长：前台 1小时23分／可见 1小时30分（非耗电值，仅活跃度参考）
 *   【本 App 流量（开机累计）】上行 12.3 MB／下行 45.6 MB｜合计 57.9 MB（本次开机累计，不分接口）
 *   【本 App 流量（本月）】WiFi 1.2 GB｜移动 300.0 MB（自然月累计）
 *   【整机流量（开机累计）】移动 5.0 GB｜WiFi 10.2 GB（本次开机累计）
 */
const props = defineProps<{ deviceNames?: Record<string, string> }>()

const visible = ref(false)
const record = ref<CommandRecord | null>(null)

function show(row: CommandRecord) {
  record.value = row
  visible.value = true
}
defineExpose({ show })

type TagType = 'success' | 'warning' | 'danger' | 'info'
interface Field { label: string; value: string }

const title = computed(() => '电/流量统计 · 查看详情')
const rawText = computed(() => record.value?.result_message || '（暂无回执）')
const deviceLabel = computed(() => {
  const id = record.value?.device_id ?? ''
  return props.deviceNames?.[id] || id
})
const statusText = computed(() => {
  switch (record.value?.status) {
    case 'success': return '执行成功'
    case 'failed': return '执行失败'
    default: return '待回执'
  }
})
const statusTagType = computed<TagType>(() => {
  switch (record.value?.status) {
    case 'success': return 'success'
    case 'failed': return 'danger'
    default: return 'info'
  }
})

const msg = computed(() => record.value?.result_message || '')

/** 截取「【电量】…」段落（到下一个【 或换行前）。 */
function sectionOf(head: string): string {
  const m = msg.value
  const idx = m.indexOf(head)
  if (idx < 0) return ''
  const start = idx + head.length
  const rest = m.slice(start)
  const end = rest.search(/【|\n/)
  return end < 0 ? rest.trim() : rest.slice(0, end).trim()
}

// ---- ① 电量 ----
interface BatteryInfo {
  pct: string | null
  status: string
  plug: string
  charging: boolean
}
const battery = computed<BatteryInfo | null>(() => {
  const s = sectionOf('【电量】')
  if (!s) return null
  const pctM = s.match(/电量：(\d+)%/)
  const statusM = s.match(/（(.+?)）/)
  const status = statusM?.[1] || '未知'
  const charging = status.startsWith('充电中') || status === '已充满'
  // 充电方式藏在状态括号里（如「充电中·AC 电源」）
  const plugM = status.match(/·(.+)$/)
  return {
    pct: pctM ? pctM[1] : null,
    status,
    plug: plugM ? plugM[1] : '',
    charging,
  }
})

/** 环形电量圈的渐变角度（0~360，按百分比换算）。 */
const ringStyle = computed(() => {
  const p = Number(battery.value?.pct ?? 0)
  const deg = Math.min(360, Math.max(0, (p / 100) * 360))
  return { background: `conic-gradient(var(--el-color-primary) ${deg}deg, #e4e7ed ${deg}deg)` }
})

/** 电量网格字段：温度 / 电压 / 健康度 / 电池技术。 */
const batteryFields = computed<Field[]>(() => {
  const s = sectionOf('【电量】')
  if (!s) return []
  const out: Field[] = []
  const push = (label: string, re: RegExp) => {
    const m = s.match(re)
    if (m) out.push({ label, value: m[1].trim() })
  }
  push('温度', /温度：([^｜）]+)/)
  push('电压', /电压：([^｜）]+)/)
  push('健康度', /健康度：([^｜）]+)/)
  push('电池', /电池：([^｜）]+)/)
  return out
})

/** 本 App 活跃时长（电量段落末尾）。 */
const active = computed(() => {
  const s = sectionOf('【电量】')
  if (!s) return ''
  const m = s.match(/本 App 活跃时长：([^（]+)/)
  return m ? m[1].trim() : ''
})

// ---- ② 流量 ----
interface TrafficRow { label: string; value: string }
interface TrafficCard {
  title: string
  rows: TrafficRow[]
  note: string
  fallback: string
}

/** 解析「键 值｜键 值（说明）」类字段（上行/下行/合计/WiFi/移动）。 */
function parseTraffic(s: string): TrafficRow[] {
  const rows: TrafficRow[] = []
  const labelMap: Record<string, string> = {
    '上行': '上行', '下行': '下行', '合计': '合计', 'WiFi': 'WiFi', '移动': '移动',
  }
  for (const seg of s.split('｜')) {
    const kv = seg.match(/^(上行|下行|合计|WiFi|移动)\s*(.+)$/)
    if (kv) {
      // 剥离末尾「（...）」说明文字（如「本次开机累计，不分接口」）
      const value = kv[2].replace(/（[^）]*）$/, '').trim()
      rows.push({ label: labelMap[kv[1]] || kv[1], value })
    }
  }
  return rows
}

const trafficCards = computed<TrafficCard[]>(() => {
  const appBoot = sectionOf('【本 App 流量（开机累计）】')
  const appMonth = sectionOf('【本 App 流量（本月）】')
  const totalBoot = sectionOf('【整机流量（开机累计）】')

  const appBootRows = appBoot ? parseTraffic(appBoot) : []
  const appMonthRows = appMonth ? parseTraffic(appMonth) : []
  const totalBootRows = totalBoot ? parseTraffic(totalBoot) : []

  return [
    {
      title: '本 App 流量（开机累计）',
      rows: appBootRows,
      note: '本次开机累计 · 不分接口（分接口口径见「本月」）',
      fallback: appBoot || '未取得',
    },
    {
      title: '本 App 流量（本月）',
      rows: appMonthRows,
      note: '系统按自然月归档 · 需「使用情况访问权」',
      fallback: appMonth || '未取得（需「使用情况访问权」）',
    },
    {
      title: '整机流量（开机累计）',
      rows: totalBootRows,
      note: '本次开机累计 · 零权限',
      fallback: totalBoot || '未取得',
    },
  ]
})

async function copyRaw() {
  try {
    await navigator.clipboard.writeText(rawText.value)
    ElMessage.success('已复制回执原文')
  } catch {
    ElMessage.warning('复制失败，请手动选择下方文本')
  }
}
</script>

<style scoped>
.dstat-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding-bottom: 12px;
  margin-bottom: 14px;
  border-bottom: 1px solid #ebeef5;
  color: #606266;
  font-size: 12px;
}
.dstat-section { margin-bottom: 18px; }
.dstat-section-title {
  font-weight: 600;
  color: #303133;
  margin-bottom: 10px;
  font-size: 14px;
}
.dstat-empty { color: #909399; font-size: 12px; padding: 6px 0; }

/* 电量：环形大字 + 状态 */
.battery-row { display: flex; align-items: center; }
.battery-hero { display: flex; align-items: center; gap: 20px; }
.battery-ring {
  width: 88px;
  height: 88px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  flex-direction: column;
  position: relative;
  flex-shrink: 0;
}
.battery-ring::after {
  content: '';
  position: absolute;
  inset: 8px;
  border-radius: 50%;
  background: #fff;
}
.battery-pct {
  position: relative;
  z-index: 1;
  font-size: 26px;
  font-weight: 700;
  color: #303133;
  line-height: 1;
}
.battery-unit {
  position: relative;
  z-index: 1;
  font-size: 11px;
  color: #909399;
}
.battery-side { display: flex; flex-direction: column; gap: 6px; }
.battery-status { display: flex; align-items: center; gap: 8px; }
.battery-plug { color: #606266; font-size: 12px; }
.battery-note { color: #909399; font-size: 12px; }

.dstat-kv {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
  margin-top: 12px;
}
.dstat-kv-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 7px 10px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 12px;
  min-width: 0;
}
.dstat-kv-label { color: #909399; flex-shrink: 0; }
.dstat-kv-value { color: #303133; word-break: break-all; }

.dstat-active {
  margin-top: 12px;
  padding: 10px 12px;
  background: #f0f9eb;
  border-left: 3px solid #67c23a;
  border-radius: 4px;
  font-size: 13px;
}
.dstat-active-label { color: #606266; margin-right: 8px; }
.dstat-active-value { color: #303133; font-weight: 600; }
.dstat-active-note { color: #909399; font-size: 12px; }

/* 流量卡片 */
.traffic-cards { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; }
.traffic-card {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px 12px;
  background: #fff;
}
.traffic-card-title { font-size: 12px; color: #606266; font-weight: 600; margin-bottom: 8px; }
.traffic-card-body { display: flex; flex-direction: column; gap: 6px; }
.traffic-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 12px;
}
.traffic-row-label { color: #909399; }
.traffic-row-value { color: #303133; font-weight: 600; }
.traffic-row-empty { color: #909399; font-size: 12px; }
.traffic-card-note { margin-top: 8px; padding-top: 6px; border-top: 1px dashed #ebeef5; color: #c0c4cc; font-size: 11px; line-height: 1.4; }

.dstat-raw { margin-top: 6px; }
.dstat-raw-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 6px;
}
.dstat-raw-body {
  margin: 0;
  padding: 10px 12px;
  max-height: 200px;
  overflow: auto;
  background: #f5f7fa;
  border-radius: 4px;
  color: #606266;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
  user-select: text;
}
</style>
