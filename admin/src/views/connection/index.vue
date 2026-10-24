<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="title">活跃连接（{{ activeCount }} 条）</span>
          <div>
            <el-button
              :type="autoRefresh ? 'primary' : 'default'"
              :icon="autoRefresh ? VideoPause : VideoPlay"
              size="small"
              @click="toggleAutoRefresh"
            >
              {{ autoRefresh ? `自动刷新中（${countdown}s）` : '自动刷新' }}
            </el-button>
            <el-button type="primary" :icon="Refresh" size="small" @click="load">
              刷新
            </el-button>
          </div>
        </div>
      </template>

      <el-table :data="connections" v-loading="loading" stripe>
        <el-table-column prop="device_id" label="设备 ID" min-width="200" show-overflow-tooltip />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span class="dot" :class="stateDot(row.state)" />
            <span class="state-text">{{ stateText(row.state) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="已运行" width="130">
          <template #default="{ row }">
            {{ formatDuration(row.uptime_seconds) }}
          </template>
        </el-table-column>
        <el-table-column label="连接时间" width="180">
          <template #default="{ row }">{{ formatTime(row.connected_at) }}</template>
        </el-table-column>
        <el-table-column label="最后活跃" width="180">
          <template #default="{ row }">{{ formatTime(row.last_active_at) }}</template>
        </el-table-column>
        <el-table-column label="空闲" width="110">
          <template #default="{ row }">{{ formatDuration(row.idle_seconds) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="120" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="danger" @click="handleDisconnect(row)">
              断开
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 上下线记录 -->
    <el-card class="event-card" shadow="never">
      <template #header>
        <div class="card-header">
          <span class="title">上下线记录（{{ eventTotal }} 条）</span>
          <div>
            <el-select
              v-model="eventDeviceFilter"
              placeholder="全部设备"
              clearable
              size="small"
              style="width: 220px"
              @change="loadEvents"
            >
              <el-option
                v-for="d in deviceOptions"
                :key="d.value"
                :label="d.label"
                :value="d.value"
              />
            </el-select>
            <el-button :icon="Refresh" size="small" @click="loadEvents" style="margin-left: 8px">
              刷新
            </el-button>
          </div>
        </div>
      </template>

      <div v-if="events.length === 0" class="empty-events">
        <el-empty description="暂无上下线记录" :image-size="60" />
      </div>
      <el-timeline v-else class="event-timeline">
        <el-timeline-item
          v-for="(e, i) in events"
          :key="i"
          :type="e.event === 'online' ? 'success' : 'danger'"
          :timestamp="formatTime(e.ts)"
        >
          <div class="event-row">
            <span class="dot" :class="e.event === 'online' ? 'dot-online' : 'dot-offline'" />
            <span class="event-device">{{ deviceNameOf(e.device_id) }}</span>
            <el-tag :type="e.event === 'online' ? 'success' : 'danger'" size="small">
              {{ e.event === 'online' ? '上线' : '下线' }}
            </el-tag>
            <span v-if="e.reason" class="event-reason">{{ reasonText(e.reason) }}</span>
          </div>
        </el-timeline-item>
      </el-timeline>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, VideoPlay, VideoPause } from '@element-plus/icons-vue'
import { listConnections, listConnectionLog } from '@/api/connections'
import { listDevices, disconnectDevice } from '@/api/devices'
import { formatDuration, formatTime } from '@/utils/format'
import type { ConnectionInfo, ConnectionEvent, Device } from '@/types'

const connections = ref<ConnectionInfo[]>([])
const activeCount = ref(0)
const loading = ref(false)
const events = ref<ConnectionEvent[]>([])
const eventTotal = ref(0)
const eventDeviceFilter = ref('')
const deviceNameMap = ref<Record<string, string>>({})

const autoRefresh = ref(false)
const autoInterval = 3000 // 自动刷新间隔（毫秒）
const countdown = ref(autoInterval / 1000) // 倒计时显示（秒）
let timer: number | null = null
let countdownTimer: number | null = null

const deviceOptions = computed(() =>
  Object.entries(deviceNameMap.value).map(([id, name]) => ({
    value: id,
    label: name || id,
  })),
)

function deviceNameOf(id: string) {
  return deviceNameMap.value[id] || id
}

function stateText(state: string) {
  const s = (state || '').toUpperCase()
  if (s === 'ACTIVE') return '在线'
  if (s === 'CONNECTING') return '连接中'
  if (s === 'CLOSED') return '离线'
  return state || '未知'
}

function stateDot(state: string) {
  const s = (state || '').toUpperCase()
  if (s === 'ACTIVE') return 'dot-online'
  if (s === 'CONNECTING') return 'dot-connecting'
  return 'dot-offline'
}

function reasonText(reason: string) {
  const map: Record<string, string> = {
    closed: '主动断开',
    read_timeout: '读超时',
    replaced: '被新连接顶替',
    server_kick: '服务端踢下线',
    disconnected: '客户端断开',
    error: '异常断开',
  }
  return map[reason] || reason
}

async function load() {
  loading.value = true
  try {
    const res = await listConnections()
    connections.value = res.connections
    activeCount.value = res.active_connections
  } finally {
    loading.value = false
  }
}

async function loadEvents() {
  const res = await listConnectionLog({
    device_id: eventDeviceFilter.value || undefined,
    limit: 100,
  })
  events.value = res.events
  eventTotal.value = res.total
}

async function loadDeviceNames() {
  const devs: Device[] = await listDevices()
  deviceNameMap.value = Object.fromEntries(
    devs.map((d) => [d.device_id, d.device_name || d.device_id]),
  )
}

function toggleAutoRefresh() {
  autoRefresh.value = !autoRefresh.value
  if (autoRefresh.value) {
    startAutoRefresh()
    ElMessage.success(`已开启自动刷新（每 ${autoInterval / 1000} 秒）`)
  } else {
    stopAutoRefresh()
    ElMessage.info('已关闭自动刷新')
  }
}

function startAutoRefresh() {
  countdown.value = autoInterval / 1000
  // 每秒倒计时
  countdownTimer = window.setInterval(() => {
    countdown.value -= 1
    if (countdown.value <= 0) {
      countdown.value = autoInterval / 1000
    }
  }, 1000)
  // 按间隔刷新数据
  timer = window.setInterval(load, autoInterval)
}

function stopAutoRefresh() {
  if (timer) window.clearInterval(timer)
  if (countdownTimer) window.clearInterval(countdownTimer)
  timer = null
  countdownTimer = null
}

async function handleDisconnect(row: ConnectionInfo) {
  try {
    await ElMessageBox.confirm(`确定断开设备「${row.device_id}」吗？`, '提示', {
      type: 'warning',
    })
  } catch {
    return
  }
  await disconnectDevice(row.device_id)
  ElMessage.success('已断开')
  load()
  loadEvents()
}

onMounted(() => {
  load()
  loadEvents()
  loadDeviceNames()
})

onBeforeUnmount(() => {
  stopAutoRefresh()
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.state-text {
  font-size: 13px;
}
.event-card {
  margin-top: 16px;
}
.event-timeline {
  max-height: 400px;
  overflow-y: auto;
  padding: 8px 4px;
}
.event-row {
  display: flex;
  align-items: center;
  gap: 8px;
}
.event-device {
  font-weight: 600;
  color: #303133;
}
.event-reason {
  font-size: 12px;
  color: #909399;
}
.empty-events {
  padding: 12px 0;
}
</style>
