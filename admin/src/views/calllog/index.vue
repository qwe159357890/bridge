<template>
  <div>
    <el-card>
      <div class="toolbar">
        <div class="left">
          <DevicePicker v-model="deviceId" :devices="devices" placeholder="选择设备" />
          <el-select v-model="callType" style="width: 150px" @change="load(0)">
            <el-option label="全部" :value="undefined" />
            <el-option label="呼入" :value="1" />
            <el-option label="呼出" :value="2" />
            <el-option label="未接" :value="3" />
          </el-select>
          <el-input
            v-model="keyword"
            placeholder="搜索号码 / 联系人"
            clearable
            style="width: 260px"
            @keyup.enter="load(0)"
            @clear="load(0)"
          />
          <el-button type="primary" :disabled="!deviceId" @click="load(0)">查询</el-button>
          <el-button :loading="refreshing" :disabled="!deviceId" @click="refreshFromDevice">
            {{ refreshing ? '拉取中…' : '🔄 从设备刷新' }}
          </el-button>
        </div>
        <el-button
          type="danger"
          plain
          :disabled="!deviceId || items.length === 0"
          @click="clearAll"
        >
          清空该设备
        </el-button>
      </div>

      <el-alert
        v-if="syncTime"
        :title="`快照采集时间：${syncTime}（每次下发「获取通话记录」命令整体覆盖该设备快照）`"
        type="info"
        :closable="false"
        show-icon
        class="hint"
      />

      <el-table :data="items" v-loading="loading" stripe size="small" max-height="560">
        <el-table-column label="类型" width="80">
          <template #default="{ row }">
            <el-tag :type="tagType(row.call_type)" size="small">{{ row.call_type_name }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="number" label="号码" width="150" show-overflow-tooltip />
        <el-table-column label="所属卡" width="150">
          <template #default="{ row }">
            <span v-if="row.sim_slot >= 0">{{ simLabel(row) }}</span>
            <span v-else class="dim">未知</span>
          </template>
        </el-table-column>
        <el-table-column label="联系人" width="130" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.name">{{ row.name }}</span>
            <span v-else class="dim">未知</span>
          </template>
        </el-table-column>
        <el-table-column label="时长" width="100">
          <template #default="{ row }">{{ formatDuration(row.duration) }}</template>
        </el-table-column>
        <el-table-column prop="location" label="归属地" width="140" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.location">{{ row.location }}</span>
            <span v-else class="dim">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="call_time" label="时间" width="160" />
        <template #empty>
          <el-empty
            :description="
              deviceId
                ? '该设备暂无通话记录快照（先在「命令下发」页下发「获取通话记录」命令）'
                : '请先选择设备'
            "
            :image-size="80"
          />
        </template>
      </el-table>

      <div v-if="total > queryLimit" class="pager">
        <el-pagination
          layout="prev, pager, next"
          :total="total"
          :page-size="queryLimit"
          :current-page="page + 1"
          @current-change="(p: number) => load(p - 1)"
        />
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import DevicePicker from '@/components/DevicePicker.vue'
import { listDevices } from '@/api/devices'
import { sendCommand, getCommand } from '@/api/commands'
import { listCallLogs, deleteCallLogs, simLabel, type CallLogItem } from '@/api/data'
import type { Device } from '@/types'
import { useTargetDeviceStore } from '@/stores/targetDevice'

/** 通话记录页：按设备查看通话快照（数据来自「获取通话记录」命令上报）。 */

const devices = ref<Device[]>([])
// 目标设备接全局 store（跨页面保持，刷新不丢；2026-10-28 用户需求）
const targetStore = useTargetDeviceStore()
const deviceId = computed({
  get: () => targetStore.deviceId,
  set: (v: string) => targetStore.set(v),
})
const callType = ref<number | undefined>(undefined)
const keyword = ref('')
const items = ref<CallLogItem[]>([])
const total = ref(0)
const syncTime = ref<string | null>(null)
const loading = ref(false)
const queryLimit = 300
const page = ref(0)

// ---------------- 从设备刷新（直发 get_call_log 命令 + 自动重查，照短信页模式） ----------------

const refreshing = ref(false)

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

/** 下发 get_call_log（全量覆盖该设备快照），轮询回执后自动刷新列表。 */
async function refreshFromDevice() {
  if (!deviceId.value || refreshing.value) return
  refreshing.value = true
  try {
    const r = await sendCommand(deviceId.value, 'get_call_log', { call_type: 'all', limit: 200 }, true)
    if (!r.delivered) {
      ElMessage.warning('设备不在线，无法拉取通话记录（设备连上后再试）')
      return
    }
    ElMessage.info('已下发采集命令，等待设备回执…')
    const deadline = Date.now() + 90_000
    while (Date.now() < deadline) {
      await sleep(1500)
      const c = await getCommand(r.command_id)
      if (c.status === 'success') {
        ElMessage.success('设备通话记录已拉取入库')
        load(0)
        return
      }
      if (c.status === 'failed') {
        ElMessage.error(c.result_message || '设备拉取通话记录失败')
        return
      }
    }
    ElMessage.warning('命令 90 秒未回执，稍后请手动点「查询」查看')
  } finally {
    refreshing.value = false
  }
}

onMounted(async () => {
  devices.value = await listDevices()
})

async function load(p: number) {
  if (!deviceId.value) return
  loading.value = true
  page.value = p
  try {
    const r = await listCallLogs({
      device_id: deviceId.value,
      call_type: callType.value,
      keyword: keyword.value.trim() || undefined,
      limit: queryLimit,
      offset: p * queryLimit,
    })
    items.value = r.items
    total.value = r.total
    syncTime.value = r.sync_time ?? null
  } finally {
    loading.value = false
  }
}

async function clearAll() {
  try {
    await ElMessageBox.confirm(
      `确定清空设备「${deviceId.value}」的通话记录快照（${total.value} 条）？此操作不影响手机上的真实通话记录。`,
      '危险操作',
      { type: 'warning', confirmButtonText: '清空' },
    )
  } catch {
    return
  }
  const { deleted } = await deleteCallLogs(deviceId.value)
  ElMessage.success(`已清空 ${deleted} 条`)
  items.value = []
  total.value = 0
  syncTime.value = null
}

function formatDuration(sec: number): string {
  if (sec <= 0) return '-'
  const m = Math.floor(sec / 60)
  const s = sec % 60
  return m > 0 ? `${m}分${s}秒` : `${s}秒`
}

function tagType(t: number): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  if (t === 1) return 'primary'
  if (t === 2) return 'success'
  if (t === 3 || t === 5) return 'danger'
  return 'info'
}
</script>

<style scoped>
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
}
.left {
  display: flex;
  gap: 10px;
  align-items: center;
}
.hint {
  margin-bottom: 10px;
}
.dim {
  color: #c0c4cc;
}
.pager {
  margin-top: 12px;
  display: flex;
  justify-content: center;
}
</style>
