<template>
  <div>
    <!-- 设备选择 -->
    <el-card shadow="never" class="toolbar-card">
      <div class="toolbar">
        <span class="title">极空保护板</span>
        <div class="tools">
          <el-select
            v-model="selectedDevice"
            placeholder="选择设备"
            size="small"
            style="width: 240px"
            @change="loadState"
          >
            <el-option
              v-for="d in deviceOptions"
              :key="d.value"
              :label="d.label"
              :value="d.value"
            />
          </el-select>
          <el-button :icon="Refresh" size="small" @click="loadState">刷新</el-button>
        </div>
      </div>
    </el-card>

    <!-- 未选设备 / 未连接 -->
    <el-empty
      v-if="!selectedDevice"
      description="请先选择一台设备"
      :image-size="80"
    />
    <el-empty
      v-else-if="!status || !status.connected"
      description="该设备当前未连接极空保护板（请在手机上打开极空页并连接，或下发「连接极空设备」命令）"
      :image-size="80"
    />

    <template v-else>
      <!-- 实时总览 -->
      <el-row :gutter="16">
        <!-- SOC 圆盘 -->
        <el-col :span="8">
          <el-card shadow="never" class="soc-card">
            <div class="soc-ring" :style="socRingStyle">
              <div class="soc-inner">
                <div class="soc-value">{{ status.soc ?? 0 }}<span class="soc-unit">%</span></div>
                <div class="soc-label">剩余电量 SOC</div>
              </div>
            </div>
          </el-card>
        </el-col>

        <!-- 核心指标 -->
        <el-col :span="16">
          <el-row :gutter="12">
            <el-col :span="8">
              <metric-card label="总电压" :value="fmt(status.total_voltage)" unit="V" color="#1a6ee8" />
            </el-col>
            <el-col :span="8">
              <metric-card label="电流" :value="fmt(status.current)" unit="A" :color="(status.current ?? 0) >= 0 ? '#2e9e5b' : '#ed9a3c'" />
            </el-col>
            <el-col :span="8">
              <metric-card label="功率" :value="fmt(status.power)" unit="W" color="#1f2329" />
            </el-col>
          </el-row>
          <el-row :gutter="12" style="margin-top: 12px">
            <el-col :span="8">
              <metric-card label="压差" :value="fmt(status.diff_voltage)" unit="V" color="#e5484d" />
            </el-col>
            <el-col :span="8">
              <metric-card label="温差" :value="fmt(status.temp_diff)" unit="℃" color="#ed9a3c" />
            </el-col>
            <el-col :span="8">
              <metric-card label="剩余容量" :value="fmt(status.remain_capacity)" unit="Ah" color="#8a919f" />
            </el-col>
          </el-row>
        </el-col>
      </el-row>

      <!-- MOS 状态 + 远程控制 -->
      <el-card shadow="never" class="mos-card">
        <div class="mos-row">
          <div class="mos-item">
            <span class="mos-dot" :class="status.charge_mos_on ? 'on' : 'off'" />
            <span class="mos-label">充电 MOS</span>
            <span class="mos-state">{{ status.charge_mos_on ? '开启' : '关闭' }}</span>
          </div>
          <div class="mos-item">
            <span class="mos-dot" :class="status.discharge_mos_on ? 'on' : 'off'" />
            <span class="mos-label">放电 MOS</span>
            <span class="mos-state">{{ status.discharge_mos_on ? '开启' : '关闭' }}</span>
          </div>
          <div class="mos-item">
            <span class="mos-dot" :class="status.balance_on ? 'on' : 'off'" />
            <span class="mos-label">主动均衡</span>
            <span class="mos-state">{{ status.balance_on ? '开启' : '关闭' }}</span>
          </div>
          <div class="mos-actions">
            <el-button size="small" @click="toggleMos('charge', !status.charge_mos_on)">
              {{ status.charge_mos_on ? '关充电' : '开充电' }}
            </el-button>
            <el-button size="small" @click="toggleMos('discharge', !status.discharge_mos_on)">
              {{ status.discharge_mos_on ? '关放电' : '开放电' }}
            </el-button>
            <el-button size="small" type="warning" @click="toggleBalance(!status.balance_on)">
              {{ status.balance_on ? '关均衡' : '开均衡' }}
            </el-button>
          </div>
        </div>
      </el-card>

      <!-- 单体电压 + 温度 -->
      <el-row :gutter="16">
        <el-col :span="12">
          <el-card shadow="never">
            <template #header>
              <div class="card-header">
                <span class="title">单体电压（{{ status.cell_count ?? 0 }} 串）</span>
                <span class="sub">压差 {{ fmt(status.diff_voltage) }}V</span>
              </div>
            </template>
            <div v-if="!status.cell_voltages?.length" class="empty-mini">暂无数据</div>
            <div v-else class="cell-list">
              <div
                v-for="(v, i) in status.cell_voltages"
                :key="i"
                class="cell-row"
                :class="{ 'is-max': i === status.max_cell_index, 'is-min': i === status.min_cell_index }"
              >
                <span class="cell-name">第 {{ i + 1 }} 串</span>
                <div class="cell-bar">
                  <div class="cell-bar-fill" :style="{ width: barWidth(v), background: barColor(v, i) }" />
                </div>
                <span class="cell-val">{{ fmt(v) }} V</span>
              </div>
            </div>
          </el-card>
        </el-col>
        <el-col :span="12">
          <el-card shadow="never">
            <template #header>
              <div class="card-header">
                <span class="title">温度传感器（{{ status.temp_count ?? 0 }} 个）</span>
                <span class="sub">温差 {{ fmt(status.temp_diff) }}℃</span>
              </div>
            </template>
            <div v-if="!status.temperatures?.length" class="empty-mini">暂无数据</div>
            <div v-else class="temp-list">
              <div v-for="(t, i) in status.temperatures" :key="i" class="temp-row">
                <span class="temp-name">T{{ i + 1 }}</span>
                <span class="temp-val" :style="{ color: tempColor(t) }">{{ fmt(t) }} ℃</span>
              </div>
            </div>
          </el-card>
        </el-col>
      </el-row>

      <!-- 保护状态 -->
      <el-card shadow="never" class="protect-card">
        <template #header>
          <span class="title">保护/告警状态</span>
        </template>
        <div v-if="!protectionEntries.length" class="empty-mini">暂无保护状态数据</div>
        <div v-else class="protect-grid">
          <div v-for="(e, i) in protectionEntries" :key="i" class="protect-item">
            <span class="mos-dot" :class="e.triggered ? 'on-alarm' : 'off'" />
            <span class="protect-name">{{ e.name }}</span>
            <span class="protect-state" :class="e.triggered ? 'alarm' : 'ok'">
              {{ e.triggered ? '触发' : '正常' }}
            </span>
          </div>
        </div>
      </el-card>
    </template>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { getJikongState, jikongControl, type JikongStatus } from '@/api/jikong'
import { listDevices } from '@/api/devices'

// 用内联子组件替代（避免额外文件），metric-card 用函数式渲染
import { defineComponent, h } from 'vue'

const MetricCard = defineComponent({
  props: {
    label: String,
    value: String,
    unit: String,
    color: { type: String, default: '#1f2329' },
  },
  setup(props) {
    return () =>
      h('div', { class: 'metric-card' }, [
        h('div', { class: 'metric-label' }, props.label),
        h(
          'div',
          { class: 'metric-value', style: { color: props.color } },
          [props.value, h('span', { class: 'metric-unit' }, props.unit || '')],
        ),
      ])
  },
})

const selectedDevice = ref('')
const status = ref<JikongStatus | null>(null)
const deviceNameMap = ref<Record<string, string>>({})

const deviceOptions = computed(() =>
  Object.entries(deviceNameMap.value).map(([id, name]) => ({ value: id, label: name || id })),
)

const protectionEntries = computed(() => {
  const s = status.value
  if (!s?.protection_states) return []
  return Object.entries(s.protection_states).map(([name, triggered]) => ({ name, triggered: !!triggered }))
})

const socRingStyle = computed(() => {
  const soc = Math.max(0, Math.min(100, status.value?.soc ?? 0))
  const deg = Math.round(270 * (soc / 100))
  return {
    background: `conic-gradient(#2e9e5b ${deg}deg, #eef0f3 ${deg}deg)`,
  }
})

function fmt(v: number | undefined): string {
  if (v === undefined || v === null) return '--'
  return Math.abs(v) >= 100 ? v.toFixed(1) : v.toFixed(2)
}

function barWidth(v: number): string {
  const max = status.value?.max_cell_voltage ?? 0
  if (max <= 0) return '0%'
  return `${Math.max(0, Math.min(100, (v / max) * 100))}%`
}

function barColor(v: number, i: number): string {
  if (i === status.value?.max_cell_index) return '#e5484d'
  if (i === status.value?.min_cell_index) return '#ed9a3c'
  return '#1a6ee8'
}

function tempColor(t: number): string {
  if (t >= 55) return '#e5484d'
  if (t >= 45) return '#ed9a3c'
  return '#1a6ee8'
}

async function loadState() {
  if (!selectedDevice.value) {
    status.value = null
    return
  }
  try {
    const res = await getJikongState(selectedDevice.value)
    status.value = res.status
  } catch {
    /* 错误已由拦截器提示 */
  }
}

async function loadDeviceNames() {
  const devs = await listDevices()
  deviceNameMap.value = Object.fromEntries(devs.map((d) => [d.device_id, d.device_name || d.device_id]))
  if (!selectedDevice.value && devs.length > 0) {
    selectedDevice.value = devs[0].device_id
    loadState()
  }
}

async function toggleMos(target: 'charge' | 'discharge', on: boolean) {
  try {
    await jikongControl(selectedDevice.value, {
      action: 'mos',
      charge: target === 'charge',
      discharge: target === 'discharge',
      on,
    })
    ElMessage.success(`${target === 'charge' ? '充电' : '放电'} MOS 已${on ? '开启' : '关闭'}指令下发`)
    setTimeout(loadState, 1500)
  } catch {
    /* 已提示 */
  }
}

async function toggleBalance(on: boolean) {
  try {
    await jikongControl(selectedDevice.value, { action: 'balance', on })
    ElMessage.success(`均衡${on ? '开启' : '关闭'}指令下发`)
    setTimeout(loadState, 1500)
  } catch {
    /* 已提示 */
  }
}

onMounted(() => {
  loadDeviceNames()
})
</script>

<style scoped>
.toolbar-card {
  margin-bottom: 16px;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.tools {
  display: flex;
  gap: 8px;
  align-items: center;
}
.title {
  font-weight: 600;
  color: #303133;
}
.sub {
  font-size: 13px;
  color: #909399;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

/* SOC 圆盘 */
.soc-card {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 240px;
}
.soc-ring {
  width: 180px;
  height: 180px;
  border-radius: 50%;
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
}
.soc-ring::after {
  content: '';
  position: absolute;
  inset: 18px;
  background: #fff;
  border-radius: 50%;
}
.soc-inner {
  position: relative;
  z-index: 1;
  text-align: center;
}
.soc-value {
  font-size: 42px;
  font-weight: 700;
  color: #1f2329;
}
.soc-unit {
  font-size: 14px;
  color: #8a919f;
  margin-left: 2px;
}
.soc-label {
  font-size: 13px;
  color: #8a919f;
}

/* 指标卡 */
.metric-card {
  background: #fff;
  border-radius: 8px;
  padding: 16px;
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.04);
}
.metric-label {
  font-size: 12px;
  color: #8a919f;
}
.metric-value {
  font-size: 22px;
  font-weight: 700;
  margin-top: 6px;
}
.metric-unit {
  font-size: 13px;
  font-weight: 400;
  color: #909399;
  margin-left: 4px;
}

/* MOS 状态 */
.mos-card {
  margin-top: 16px;
}
.mos-row {
  display: flex;
  align-items: center;
  gap: 24px;
  flex-wrap: wrap;
}
.mos-item {
  display: flex;
  align-items: center;
  gap: 8px;
}
.mos-dot {
  display: inline-block;
  width: 10px;
  height: 10px;
  border-radius: 50%;
}
.mos-dot.on {
  background: #2e9e5b;
}
.mos-dot.off {
  background: #c0c4cc;
}
.mos-dot.on-alarm {
  background: #e5484d;
}
.mos-label {
  font-size: 13px;
  color: #606266;
}
.mos-state {
  font-size: 13px;
  font-weight: 600;
}
.mos-actions {
  margin-left: auto;
  display: flex;
  gap: 8px;
}

/* 单体电压 */
.cell-list {
  max-height: 360px;
  overflow-y: auto;
}
.cell-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 6px 8px;
  border-radius: 6px;
}
.cell-row.is-max,
.cell-row.is-min {
  background: #fef0f0;
}
.cell-name {
  width: 64px;
  font-size: 13px;
  color: #909399;
  flex-shrink: 0;
}
.cell-bar {
  flex: 1;
  height: 8px;
  background: #eef0f3;
  border-radius: 4px;
  overflow: hidden;
}
.cell-bar-fill {
  height: 100%;
  border-radius: 4px;
  transition: width 0.3s;
}
.cell-val {
  width: 72px;
  text-align: right;
  font-size: 13px;
  font-weight: 600;
  flex-shrink: 0;
}

/* 温度 */
.temp-list {
  max-height: 360px;
  overflow-y: auto;
}
.temp-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 8px;
  border-bottom: 1px solid #f5f7fa;
}
.temp-name {
  font-size: 13px;
  color: #909399;
}
.temp-val {
  font-size: 17px;
  font-weight: 700;
}

/* 保护状态 */
.protect-card {
  margin-top: 16px;
}
.protect-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(180px, 1fr));
  gap: 12px;
}
.protect-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 12px;
  border-radius: 6px;
  background: #f5f7fa;
}
.protect-name {
  flex: 1;
  font-size: 13px;
}
.protect-state {
  font-size: 13px;
  font-weight: 600;
}
.protect-state.alarm {
  color: #e5484d;
}
.protect-state.ok {
  color: #2e9e5b;
}

.empty-mini {
  padding: 24px 0;
  text-align: center;
  color: #c0c4cc;
  font-size: 13px;
}
</style>
