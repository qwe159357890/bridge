<template>
  <div>
    <!-- 统计概览 -->
    <el-row :gutter="16" class="stats-row">
      <el-col :span="8">
        <el-card shadow="never" class="stat-card">
          <div class="stat">
            <div class="stat-icon" style="background: rgba(64, 158, 255, 0.12); color: var(--brand)">
              <el-icon><Monitor /></el-icon>
            </div>
            <div>
              <div class="stat-number">{{ devices.length }}</div>
              <div class="stat-label">设备总数</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="stat-card">
          <div class="stat">
            <div class="stat-icon" style="background: rgba(103, 194, 58, 0.12); color: var(--success)">
              <el-icon><Connection /></el-icon>
            </div>
            <div>
              <div class="stat-number">{{ onlineCount }}</div>
              <div class="stat-label">在线设备</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="stat-card">
          <div class="stat">
            <div class="stat-icon" style="background: rgba(144, 147, 153, 0.12); color: var(--info)">
              <el-icon><CircleClose /></el-icon>
            </div>
            <div>
              <div class="stat-number">{{ offlineCount }}</div>
              <div class="stat-label">离线设备</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="title">设备列表</span>
          <div class="header-actions">
            <el-input
              v-model="keyword"
              placeholder="按昵称或设备 ID 搜索"
              clearable
              class="search"
              :prefix-icon="Search"
            />
            <el-button type="primary" :icon="Refresh" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table :data="filteredDevices" v-loading="loading" stripe>
        <el-table-column label="设备昵称" min-width="160">
          <template #default="{ row }">
            <span class="device-name">{{ row.device_name }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="device_id" label="设备 ID" min-width="220" show-overflow-tooltip />
        <el-table-column label="在线状态" width="110">
          <template #default="{ row }">
            <DeviceStatusTag :online="row.online" />
          </template>
        </el-table-column>
        <el-table-column label="注册时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.created_at) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="goCommand(row)">
              下发命令
            </el-button>
            <el-button size="small" @click="goLog(row)">查看日志</el-button>
            <el-button
              size="small"
              type="danger"
              plain
              :disabled="!row.online"
              @click="handleDisconnect(row)"
            >
              踢下线
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无设备" :image-size="80" />
        </template>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search, Monitor, Connection, CircleClose } from '@element-plus/icons-vue'
import { listDevices, disconnectDevice } from '@/api/devices'
import DeviceStatusTag from '@/components/DeviceStatusTag.vue'
import { formatTime } from '@/utils/format'
import type { Device } from '@/types'

const router = useRouter()
const devices = ref<Device[]>([])
const keyword = ref('')
const loading = ref(false)

const filteredDevices = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return devices.value
  return devices.value.filter(
    (d) =>
      d.device_name.toLowerCase().includes(k) ||
      d.device_id.toLowerCase().includes(k),
  )
})

const onlineCount = computed(() => devices.value.filter((d) => d.online).length)
const offlineCount = computed(() => devices.value.filter((d) => !d.online).length)

async function load() {
  loading.value = true
  try {
    devices.value = await listDevices()
  } finally {
    loading.value = false
  }
}

function goCommand(device: Device) {
  router.push({ path: '/command', query: { deviceId: device.device_id } })
}

function goLog(device: Device) {
  router.push({ path: '/log', query: { deviceId: device.device_id } })
}

async function handleDisconnect(device: Device) {
  try {
    await ElMessageBox.confirm(
      `确定要踢下线设备「${device.device_name}」吗？`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  await disconnectDevice(device.device_id)
  ElMessage.success('已踢下线')
  load()
}

onMounted(load)
</script>

<style scoped>
.stats-row {
  margin-bottom: 16px;
}
.stat {
  display: flex;
  align-items: center;
  gap: 14px;
}
.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
}
.stat-label {
  font-size: 13px;
  color: #909399;
  margin-top: 2px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.search {
  width: 260px;
}
.device-name {
  font-weight: 600;
  color: #303133;
}
</style>
