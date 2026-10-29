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
                <el-badge :value="d.log_count" :max="9999" class="badge" />
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

        <!-- 右侧：日志详情（按行列表） -->
        <div class="log-detail">
          <template v-if="currentDeviceId">
            <div class="toolbar">
              <span class="section-title">
                {{ deviceNameOf(currentDeviceId) }} 的日志（共 {{ total }} 行）
              </span>
              <el-button
                type="danger"
                size="small"
                :icon="Delete"
                @click="handleClear"
              >
                清除该设备日志
              </el-button>
            </div>

            <!-- 过滤栏 -->
            <div class="filter-bar">
              <el-select v-model="filterLevel" placeholder="级别" clearable size="small" class="filter-level">
                <el-option v-for="lv in levels" :key="lv.value" :label="lv.label" :value="lv.value" />
              </el-select>
              <el-input
                v-model="filterTag"
                placeholder="模块标签（如 中继/解码）"
                clearable
                size="small"
                class="filter-tag"
                @change="applyFilter"
              />
              <el-input
                v-model="filterKeyword"
                placeholder="关键字搜索"
                clearable
                size="small"
                class="filter-keyword"
                :prefix-icon="Search"
                @change="applyFilter"
              />
              <el-button size="small" @click="resetFilter">重置</el-button>
            </div>

            <!-- 日志行表格 -->
            <el-table
              :data="logs"
              border
              stripe
              size="small"
              height="480"
              class="log-table"
              empty-text="该设备暂无日志"
            >
              <el-table-column label="#" type="index" width="70" align="center">
                <template #default="{ $index }">
                  <span class="row-index">{{ total - (page - 1) * pageSize - $index }}</span>
                </template>
              </el-table-column>
              <el-table-column label="时间" width="150">
                <template #default="{ row }">
                  <span class="log-time">{{ formatLogTime(row.ts) }}</span>
                </template>
              </el-table-column>
              <el-table-column label="级别" width="70" align="center">
                <template #default="{ row }">
                  <span class="level-badge" :class="'level-' + row.level.toLowerCase()">{{ row.level }}</span>
                </template>
              </el-table-column>
              <el-table-column label="模块" width="110">
                <template #default="{ row }">
                  <span class="log-tag">{{ row.tag || '—' }}</span>
                </template>
              </el-table-column>
              <el-table-column label="内容" min-width="320">
                <template #default="{ row }">
                  <span class="log-message" :class="'msg-' + row.level.toLowerCase()">{{ row.message }}</span>
                </template>
              </el-table-column>
            </el-table>

            <!-- 分页器（只要有日志就常驻） -->
            <div class="pagination-row">
              <el-pagination
                v-if="total > 0"
                background
                layout="total, sizes, prev, pager, next, jumper"
                :total="total"
                :page-sizes="[20, 50, 100, 200]"
                :current-page="page"
                :page-size="pageSize"
                @current-change="handlePageChange"
                @size-change="handleSizeChange"
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
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search, Delete } from '@element-plus/icons-vue'
import { listLogDevices, getDeviceLogs, clearDeviceLogs } from '@/api/logs'
import { formatTime } from '@/utils/format'
import type { LogEntry, LogDeviceSummary } from '@/types'

const route = useRoute()
const devices = ref<LogDeviceSummary[]>([])
const logs = ref<LogEntry[]>([])
const currentDeviceId = ref('')
const keyword = ref('')
// 分页状态
const page = ref(1)
const pageSize = ref(50)
const total = ref(0)
// 过滤状态
const filterLevel = ref('')
const filterTag = ref('')
const filterKeyword = ref('')

const levels = [
  { label: 'E 错误', value: 'E' },
  { label: 'W 警告', value: 'W' },
  { label: 'I 信息', value: 'I' },
  { label: 'D 调试', value: 'D' },
]

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

// 格式化日志行时间（ts 为 epoch 毫秒）
function formatLogTime(ts: number) {
  if (!ts) return '—'
  const d = new Date(ts)
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}.${String(d.getMilliseconds()).padStart(3, '0')}`
}

async function loadDevices() {
  const res = await listLogDevices()
  devices.value = res.devices
  const queryId = route.query.deviceId as string
  if (queryId && devices.value.some((d) => d.device_id === queryId)) {
    currentDeviceId.value = queryId
    loadLogs()
  } else if (!currentDeviceId.value && devices.value.length > 0) {
    currentDeviceId.value = devices.value[0].device_id
    loadLogs()
  }
}

async function loadLogs() {
  if (!currentDeviceId.value) return
  const res = await getDeviceLogs(
    currentDeviceId.value,
    (page.value - 1) * pageSize.value,
    pageSize.value,
    { level: filterLevel.value, tag: filterTag.value, keyword: filterKeyword.value },
  )
  logs.value = res.logs
  total.value = res.total
}

function selectDevice(id: string) {
  currentDeviceId.value = id
  page.value = 1
  resetFilter(false)
  loadLogs()
}

function applyFilter() {
  page.value = 1
  loadLogs()
}

function resetFilter(reload = true) {
  filterLevel.value = ''
  filterTag.value = ''
  filterKeyword.value = ''
  if (reload) {
    page.value = 1
    loadLogs()
  }
}

watch(filterLevel, () => applyFilter())

function handlePageChange(p: number) {
  page.value = p
  loadLogs()
}

function handleSizeChange(s: number) {
  pageSize.value = s
  page.value = 1
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
  page.value = 1
  loadDevices()
  loadLogs()
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
  max-height: 600px;
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
.dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  display: inline-block;
}
.dot-online {
  background: #67c23a;
}
.dot-offline {
  background: #c0c4cc;
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
.filter-bar {
  display: flex;
  gap: 8px;
  margin-bottom: 12px;
  align-items: center;
}
.filter-level {
  width: 110px;
}
.filter-tag {
  width: 180px;
}
.filter-keyword {
  width: 200px;
}
.log-table {
  width: 100%;
}
.row-index {
  color: #909399;
  font-family: Consolas, monospace;
  font-size: 12px;
}
.log-time {
  font-family: Consolas, monospace;
  font-size: 12px;
  color: #606266;
  white-space: nowrap;
}
.log-tag {
  font-size: 12px;
  color: #409eff;
  font-family: Consolas, monospace;
}
.log-message {
  font-size: 12px;
  font-family: 'SFMono-Regular', Consolas, monospace;
  word-break: break-all;
  white-space: pre-wrap;
  line-height: 1.5;
}
.level-badge {
  display: inline-block;
  padding: 0 6px;
  border-radius: 3px;
  font-size: 12px;
  font-weight: 600;
  font-family: Consolas, monospace;
}
.level-e {
  background: #fef0f0;
  color: #f56c6c;
}
.level-w {
  background: #fdf6ec;
  color: #e6a23c;
}
.level-i {
  background: #f4f4f5;
  color: #909399;
}
.level-d {
  background: #ecf5ff;
  color: #409eff;
}
.level-v {
  background: #f4f4f5;
  color: #c0c4cc;
}
.msg-e {
  color: #f56c6c;
}
.msg-w {
  color: #e6a23c;
}
.pagination-row {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
}
</style>
