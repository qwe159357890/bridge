<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span>活跃连接（{{ activeCount }} 条）</span>
          <div>
            <el-button type="primary" :icon="Refresh" size="small" @click="load">
              刷新
            </el-button>
            <el-button :icon="RefreshRight" size="small" @click="startAutoRefresh">
              自动刷新
            </el-button>
          </div>
        </div>
      </template>

      <el-table :data="connections" v-loading="loading" stripe>
        <el-table-column prop="device_id" label="设备 ID" min-width="220" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="row.state === 'active' ? 'success' : 'info'" size="small">
              {{ row.state }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="连接时间" width="180">
          <template #default="{ row }">{{ formatTime(row.connected_at) }}</template>
        </el-table-column>
        <el-table-column label="最后活跃" width="180">
          <template #default="{ row }">{{ formatTime(row.last_active_at) }}</template>
        </el-table-column>
        <el-table-column label="空闲（秒）" width="120">
          <template #default="{ row }">{{ row.idle_seconds }}</template>
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
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, RefreshRight } from '@element-plus/icons-vue'
import { listConnections } from '@/api/connections'
import { disconnectDevice } from '@/api/devices'
import type { ConnectionInfo } from '@/types'

const connections = ref<ConnectionInfo[]>([])
const activeCount = ref(0)
const loading = ref(false)
let timer: number | null = null

function formatTime(ts: number) {
  if (!ts) return '-'
  return new Date(ts * 1000).toLocaleString('zh-CN')
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

function startAutoRefresh() {
  if (timer) return
  timer = window.setInterval(load, 3000)
  ElMessage.success('已开启自动刷新（每 3 秒）')
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
}

onMounted(load)

onBeforeUnmount(() => {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
</style>
