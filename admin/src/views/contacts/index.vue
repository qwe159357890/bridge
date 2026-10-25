<template>
  <div>
    <el-card>
      <div class="toolbar">
        <div class="left">
          <DevicePicker v-model="deviceId" :devices="devices" placeholder="选择设备" />
          <el-input
            v-model="keyword"
            placeholder="搜索姓名 / 号码 / 邮箱"
            clearable
            style="width: 280px"
            @keyup.enter="load(0)"
            @clear="load(0)"
          />
          <el-button type="primary" :disabled="!deviceId" @click="load(0)">查询</el-button>
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
        :title="`快照采集时间：${syncTime}（每次下发「获取通讯录」命令整体覆盖该设备快照）`"
        type="info"
        :closable="false"
        show-icon
        class="hint"
      />

      <el-table :data="items" v-loading="loading" stripe size="small" max-height="560">
        <el-table-column label="姓名" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">{{ row.name || '（未命名）' }}</template>
        </el-table-column>
        <el-table-column label="电话" min-width="200">
          <template #default="{ row }">
            <div v-for="(p, i) in row.phones" :key="i" class="multi-line">{{ p }}</div>
            <span v-if="row.phones.length === 0" class="dim">-</span>
          </template>
        </el-table-column>
        <el-table-column label="邮箱" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">
            <div v-for="(e, i) in row.emails" :key="i" class="multi-line">{{ e }}</div>
            <span v-if="row.emails.length === 0" class="dim">-</span>
          </template>
        </el-table-column>
        <el-table-column prop="sync_time" label="采集时间" width="160" />
        <template #empty>
          <el-empty
            :description="
              deviceId
                ? '该设备暂无通讯录快照（先在「命令下发」页下发「获取通讯录」命令）'
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
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import DevicePicker from '@/components/DevicePicker.vue'
import { listDevices } from '@/api/devices'
import { listContacts, deleteContacts, type ContactItem } from '@/api/data'
import type { Device } from '@/types'

/** 通讯录页：按设备查看联系人快照（数据来自「获取通讯录」命令上报）。 */

const devices = ref<Device[]>([])
const deviceId = ref('')
const keyword = ref('')
const items = ref<ContactItem[]>([])
const total = ref(0)
const syncTime = ref<string | null>(null)
const loading = ref(false)
const queryLimit = 500
const page = ref(0)

onMounted(async () => {
  devices.value = await listDevices()
})

async function load(p: number) {
  if (!deviceId.value) return
  loading.value = true
  page.value = p
  try {
    const r = await listContacts({
      device_id: deviceId.value,
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
      `确定清空设备「${deviceId.value}」的通讯录快照（${total.value} 条）？此操作不影响手机上的真实通讯录。`,
      '危险操作',
      { type: 'warning', confirmButtonText: '清空' },
    )
  } catch {
    return
  }
  const { deleted } = await deleteContacts(deviceId.value)
  ElMessage.success(`已清空 ${deleted} 条`)
  items.value = []
  total.value = 0
  syncTime.value = null
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
.multi-line {
  line-height: 1.5;
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
