<template>
  <div>
    <el-card>
      <template #header>下发命令</template>
      <el-form inline>
        <el-form-item label="目标设备">
          <DevicePicker
            v-model="form.deviceId"
            :devices="devices"
            only-online
            placeholder="选择在线设备"
            style="width: 320px"
          />
        </el-form-item>
        <el-form-item label="命令类型">
          <el-select v-model="form.command" style="width: 180px">
            <el-option label="上传日志（upload_log）" value="upload_log" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :loading="sending" @click="handleSend">
            下发命令
          </el-button>
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        title="当前支持 upload_log：让设备自动上传诊断日志。回执结果会实时推送显示在下方列表。"
      />
    </el-card>

    <el-card class="list-card">
      <template #header>
        <div class="card-header">
          <span>命令历史</span>
          <div>
            <span v-if="sseConnected" class="sse-on">实时连接中</span>
            <span v-else class="sse-off">实时已断开</span>
            <el-button type="primary" :icon="Refresh" size="small" @click="load">
              刷新
            </el-button>
          </div>
        </div>
      </template>

      <el-table :data="commands" v-loading="loading" stripe>
        <el-table-column prop="command_id" label="命令 ID" width="200" show-overflow-tooltip />
        <el-table-column label="设备" min-width="140">
          <template #default="{ row }">
            {{ deviceNameOf(row.device_id) }}
          </template>
        </el-table-column>
        <el-table-column prop="command" label="命令" width="140" />
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="回执信息" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.result_message || '-' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh } from '@element-plus/icons-vue'
import { listDevices } from '@/api/devices'
import { listCommands, sendCommand } from '@/api/commands'
import { getToken } from '@/api/request'
import { wsUrl } from '@/utils/base'
import DevicePicker from '@/components/DevicePicker.vue'
import type { CommandRecord, Device } from '@/types'

const route = useRoute()
const devices = ref<Device[]>([])
const commands = ref<CommandRecord[]>([])
const loading = ref(false)
const sending = ref(false)
const sseConnected = ref(false)
let ws: WebSocket | null = null
let reconnectTimer: number | null = null

const form = reactive({
  deviceId: (route.query.deviceId as string) || '',
  command: 'upload_log',
})

function deviceNameOf(id: string) {
  const d = devices.value.find((x) => x.device_id === id)
  return d ? d.device_name : id
}

function statusType(s: string) {
  if (s === 'success') return 'success'
  if (s === 'failed') return 'danger'
  return 'warning'
}

function statusText(s: string) {
  if (s === 'success') return '成功'
  if (s === 'failed') return '失败'
  return '待回执'
}

function formatTime(ts: number) {
  if (!ts) return '-'
  return new Date(ts * 1000).toLocaleString('zh-CN')
}

async function load() {
  loading.value = true
  try {
    commands.value = await listCommands()
  } finally {
    loading.value = false
  }
}

async function loadDevices() {
  devices.value = await listDevices()
}

async function handleSend() {
  if (!form.deviceId) {
    ElMessage.warning('请选择目标设备')
    return
  }
  sending.value = true
  try {
    const r = await sendCommand(form.deviceId, form.command)
    ElMessage.success(`命令已下发（${r.command_id.slice(0, 8)}…）`)
    load()
  } catch {
    // 错误已统一提示
  } finally {
    sending.value = false
  }
}

// 实时回执推送（WebSocket）
function connectWS() {
  const token = getToken()
  if (!token) return

  // WebSocket 地址：开发态走代理（相对当前 origin），生产态用 VITE_API_BASE 直连后端
  let url = wsUrl('/api/admin/ws')
  // token 通过 query 参数传递（浏览器 WebSocket 无法自定义 header，后端兼容）
  url += `${url.includes('?') ? '&' : '?'}token=${encodeURIComponent(token)}`

  ws = new WebSocket(url)

  ws.onopen = () => {
    sseConnected.value = true
  }
  ws.onmessage = (event) => {
    try {
      const msg = JSON.parse(event.data)
      if (msg.type === 'command_result') {
        // 收到回执，刷新命令列表
        load()
        const text = msg.data.success ? '命令执行成功' : '命令执行失败'
        ElMessage.success(`${msg.data.device_id}：${text}`)
      }
    } catch {
      // 忽略解析错误
    }
  }
  ws.onclose = () => {
    sseConnected.value = false
    scheduleReconnect()
  }
  ws.onerror = () => {
    sseConnected.value = false
    ws?.close()
  }
}

function scheduleReconnect() {
  if (reconnectTimer) return
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = null
    connectWS()
  }, 3000)
}

onMounted(() => {
  loadDevices()
  load()
  connectWS()
})

onBeforeUnmount(() => {
  if (reconnectTimer) window.clearTimeout(reconnectTimer)
  reconnectTimer = null
  ws?.close()
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.list-card {
  margin-top: 16px;
}
.sse-on {
  color: #67c23a;
  font-size: 12px;
  margin-right: 12px;
}
.sse-off {
  color: #909399;
  font-size: 12px;
  margin-right: 12px;
}
</style>
