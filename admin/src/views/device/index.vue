<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>设备列表（{{ filteredDevices.length }} 台）</span>
          <el-button type="primary" :icon="Refresh" @click="load">刷新</el-button>
        </div>
      </template>

      <el-input
        v-model="keyword"
        placeholder="按昵称或设备 ID 搜索"
        clearable
        class="search"
        :prefix-icon="Search"
      />

      <el-table :data="filteredDevices" v-loading="loading" stripe>
        <el-table-column prop="device_name" label="设备昵称" min-width="140" />
        <el-table-column prop="device_id" label="设备 ID" min-width="220" />
        <el-table-column label="在线状态" width="100">
          <template #default="{ row }">
            <DeviceStatusTag :online="row.online" />
          </template>
        </el-table-column>
        <el-table-column label="注册时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.created_at) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260" fixed="right">
          <template #default="{ row }">
            <el-button size="small" @click="goCommand(row)">下发命令</el-button>
            <el-button size="small" @click="goLog(row)">查看日志</el-button>
            <el-button
              size="small"
              type="danger"
              :disabled="!row.online"
              @click="handleDisconnect(row)"
            >
              踢下线
            </el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { listDevices, disconnectDevice } from '@/api/devices'
import DeviceStatusTag from '@/components/DeviceStatusTag.vue'
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

function formatTime(ts: number) {
  if (!ts) return '-'
  return new Date(ts * 1000).toLocaleString('zh-CN')
}

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
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.search {
  width: 320px;
  margin-bottom: 16px;
}
</style>
