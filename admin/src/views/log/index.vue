<template>
  <div>
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="title">设备日志</span>
          <el-button type="primary" :icon="Refresh" size="small" @click="loadDevices">
            刷新设备
          </el-button>
        </div>
      </template>

      <div class="log-layout">
        <!-- 左侧：设备列表卡片 -->
        <div class="device-list">
          <el-input
            v-model="keyword"
            placeholder="搜索设备 ID / 昵称"
            :prefix-icon="Search"
            clearable
            size="small"
            class="search-box"
          />
          <div class="device-scroll">
            <div
              v-for="d in filteredDevices"
              :key="d.device_id"
              class="device-item"
              :class="{ active: d.device_id === currentDeviceId }"
              @click="selectDevice(d.device_id)"
            >
              <div class="device-item-head">
                <span class="dot" :class="d.online ? 'dot-online' : 'dot-offline'" />
                <span class="device-name">{{ d.device_name || d.device_id }}</span>
                <el-badge :value="d.log_count" :max="99" class="badge" />
              </div>
              <div class="device-sub">
                <span class="device-id" :title="d.device_id">{{ d.device_id }}</span>
              </div>
              <div class="device-sub">
                <span v-if="d.last_log_at" class="last-time">{{ formatTime(d.last_log_at) }}</span>
                <span v-else class="last-time">—</span>
              </div>
            </div>
            <el-empty
              v-if="filteredDevices.length === 0"
              :description="devices.length === 0 ? '暂无日志' : '无匹配设备'"
              :image-size="60"
            />
          </div>
        </div>

        <!-- 右侧：日志详情 -->
        <div class="log-detail">
          <template v-if="currentDeviceId">
            <div class="toolbar">
              <span class="section-title">
                {{ deviceNameOf(currentDeviceId) }} 的日志（{{ logs.length }} 条）
              </span>
              <div>
                <el-button
                  type="danger"
                  size="small"
                  :icon="Delete"
                  @click="handleClear"
                >
                  清除该设备日志
                </el-button>
              </div>
            </div>

            <div class="log-list">
              <div v-for="(log, i) in logs" :key="i" class="log-item">
                <div class="log-meta">
                  <span class="log-index">#{{ logs.length - i }}</span>
                  <span class="log-tag">v{{ log.app_version || '-' }}</span>
                  <span class="log-time">{{ formatTime(log.created_at) }}</span>
                  <el-button
                    link
                    size="small"
                    type="primary"
                    :icon="CopyDocument"
                    @click="copyLog(log.content)"
                  >
                    复制
                  </el-button>
                </div>
                <pre class="log-content">{{ log.content }}</pre>
              </div>
              <el-empty
                v-if="logs.length === 0"
                description="该设备暂无日志"
                :image-size="60"
              />
            </div>
          </template>
          <el-empty v-else description="请从左侧选择设备" :image-size="80" />
        </div>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search, Delete, CopyDocument } from '@element-plus/icons-vue'
import { listLogDevices, getDeviceLogs, clearDeviceLogs } from '@/api/logs'
import { formatTime } from '@/utils/format'
import type { LogEntry, LogDeviceSummary } from '@/types'

const route = useRoute()
const devices = ref<LogDeviceSummary[]>([])
const logs = ref<LogEntry[]>([])
const currentDeviceId = ref('')
const keyword = ref('')

const filteredDevices = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return devices.value
  return devices.value.filter(
    (d) =>
      d.device_id.toLowerCase().includes(kw) ||
      (d.device_name || '').toLowerCase().includes(kw),
  )
})

function deviceNameOf(id: string) {
  const d = devices.value.find((x) => x.device_id === id)
  return d ? d.device_name || d.device_id : id
}

async function loadDevices() {
  const res = await listLogDevices()
  devices.value = res.devices
  // 从路由参数预选设备
  const queryId = route.query.deviceId as string
  if (queryId && devices.value.some((d) => d.device_id === queryId)) {
    currentDeviceId.value = queryId
    loadLogs()
  } else if (!currentDeviceId.value && devices.value.length > 0) {
    // 默认选中第一个
    currentDeviceId.value = devices.value[0].device_id
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

async function copyLog(content: string) {
  try {
    await navigator.clipboard.writeText(content)
    ElMessage.success('已复制到剪贴板')
  } catch {
    ElMessage.error('复制失败，请手动复制')
  }
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
  width: 280px;
  flex-shrink: 0;
  display: flex;
  flex-direction: column;
}
.search-box {
  margin-bottom: 12px;
}
.device-scroll {
  flex: 1;
  max-height: 560px;
  overflow-y: auto;
}
.device-item {
  padding: 10px 12px;
  border: 1px solid #ebeef5;
  border-radius: 6px;
  margin-bottom: 8px;
  cursor: pointer;
  transition: all 0.2s;
}
.device-item:hover {
  border-color: #409eff;
  box-shadow: 0 2px 8px rgba(64, 158, 255, 0.12);
}
.device-item.active {
  border-color: #409eff;
  background: #ecf5ff;
}
.device-item-head {
  display: flex;
  align-items: center;
  gap: 4px;
}
.device-name {
  font-weight: 600;
  font-size: 14px;
  color: #303133;
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.badge :deep(.el-badge__content) {
  border: none;
}
.device-sub {
  margin-top: 4px;
  padding-left: 14px;
}
.device-id {
  font-size: 12px;
  color: #909399;
  font-family: Consolas, monospace;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  display: block;
}
.last-time {
  font-size: 12px;
  color: #c0c4cc;
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
.section-title {
  font-weight: 600;
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
  align-items: center;
  gap: 12px;
  margin-bottom: 8px;
}
.log-index {
  color: #409eff;
  font-weight: 600;
  font-size: 12px;
}
.log-tag {
  background: #f0f2f5;
  color: #606266;
  font-size: 12px;
  padding: 1px 8px;
  border-radius: 10px;
}
.log-time {
  color: #909399;
  font-size: 12px;
  flex: 1;
}
.log-content {
  font-family: 'SFMono-Regular', Consolas, monospace;
  font-size: 12px;
  white-space: pre-wrap;
  word-break: break-all;
  background: #f8f9fa;
  padding: 8px;
  border-radius: 4px;
  max-height: 200px;
  overflow-y: auto;
}
</style>
