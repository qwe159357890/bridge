<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>设备日志</span>
          <el-button type="primary" :icon="Refresh" size="small" @click="loadDevices">
            刷新设备
          </el-button>
        </div>
      </template>

      <div class="log-layout">
        <div class="device-list">
          <div class="section-title">有日志的设备（{{ devices.length }}）</div>
          <el-menu @select="selectDevice" :default-active="currentDeviceId">
            <el-menu-item
              v-for="d in devices"
              :key="d"
              :index="d"
            >
              <span class="device-item">{{ deviceNameOf(d) }}</span>
            </el-menu-item>
          </el-menu>
          <el-empty v-if="devices.length === 0" description="暂无日志" />
        </div>

        <div class="log-detail">
          <div class="toolbar" v-if="currentDeviceId">
            <span class="section-title">日志内容（{{ logs.length }} 条）</span>
            <el-button
              type="danger"
              size="small"
              @click="handleClear"
            >
              清除该设备日志
            </el-button>
          </div>

          <div v-if="currentDeviceId" class="log-list">
            <div v-for="(log, i) in logs" :key="i" class="log-item">
              <div class="log-meta">
                <span>#{{ logs.length - i }}</span>
                <span>v{{ log.app_version || '-' }}</span>
                <span>{{ formatTime(log.created_at) }}</span>
              </div>
              <pre class="log-content">{{ log.content }}</pre>
            </div>
            <el-empty v-if="logs.length === 0" description="该设备暂无日志" />
          </div>
          <el-empty v-else description="请从左侧选择设备" />
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { listLogDevices, getDeviceLogs, clearDeviceLogs } from '@/api/logs'
import { listDevices } from '@/api/devices'
import type { LogEntry } from '@/types'

const route = useRoute()
const devices = ref<string[]>([])
const logs = ref<LogEntry[]>([])
const currentDeviceId = ref('')
const deviceNameMap = ref<Record<string, string>>({})

function deviceNameOf(id: string) {
  return deviceNameMap.value[id] || id
}

function formatTime(ts: number) {
  if (!ts) return '-'
  return new Date(ts * 1000).toLocaleString('zh-CN')
}

async function loadDevices() {
  const [logRes, devRes] = await Promise.all([listLogDevices(), listDevices()])
  devices.value = logRes.devices
  deviceNameMap.value = Object.fromEntries(
    devRes.map((d) => [d.device_id, d.device_name]),
  )
  // 从路由参数预选设备
  const queryId = route.query.deviceId as string
  if (queryId && devices.value.includes(queryId)) {
    currentDeviceId.value = queryId
    loadLogs()
  }
}

async function loadLogs() {
  if (!currentDeviceId.value) return
  const res = await getDeviceLogs(currentDeviceId.value)
  logs.value = res.logs
}

function selectDevice(id: string) {
  currentDeviceId.value = id
  loadLogs()
}

async function handleClear() {
  try {
    await ElMessageBox.confirm('确定清除该设备的所有日志吗？', '提示', {
      type: 'warning',
    })
  } catch {
    return
  }
  await clearDeviceLogs(currentDeviceId.value)
  ElMessage.success('已清除')
  loadDevices()
}

onMounted(loadDevices)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.log-layout {
  display: flex;
  gap: 16px;
}
.device-list {
  width: 260px;
  flex-shrink: 0;
}
.section-title {
  font-weight: 600;
  margin-bottom: 12px;
}
.device-item {
  font-size: 13px;
}
.log-detail {
  flex: 1;
  min-width: 0;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.log-list {
  max-height: 560px;
  overflow-y: auto;
}
.log-item {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 12px;
  margin-bottom: 12px;
}
.log-meta {
  display: flex;
  gap: 16px;
  color: #909399;
  font-size: 12px;
  margin-bottom: 8px;
}
.log-content {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  background: #f8f9fa;
  padding: 8px;
  border-radius: 4px;
}
</style>
