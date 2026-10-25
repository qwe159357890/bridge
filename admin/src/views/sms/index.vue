<template>
  <div>
    <el-card>
      <div class="toolbar">
        <div class="left">
          <DevicePicker v-model="deviceId" :devices="devices" placeholder="选择设备" />
          <el-select v-model="smsType" style="width: 130px" @change="reload">
            <el-option label="全部" :value="undefined" />
            <el-option v-for="(n, t) in TYPE_NAMES" :key="t" :label="n" :value="Number(t)" />
          </el-select>
          <el-input
            v-model="keyword"
            placeholder="搜索号码 / 正文"
            clearable
            style="width: 200px"
            @keyup.enter="reload"
            @clear="reload"
          />
          <el-button type="primary" :disabled="!deviceId" @click="reload">查询</el-button>
        </div>
        <span class="summary">共 {{ total }} 条（累积存档，重复下发自动去重）</span>
      </div>

      <el-table :data="items" v-loading="loading" stripe size="small" max-height="560">
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag :type="tagType(row.sms_type)" size="small">{{ row.sms_type_name }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="address" label="号码" width="150" show-overflow-tooltip />
        <el-table-column label="正文" min-width="360">
          <template #default="{ row }">
            <span class="body-text">{{ row.body }}</span>
          </template>
        </el-table-column>
        <el-table-column label="状态" width="70">
          <template #default="{ row }">
            <span :class="row.read ? 'dim' : 'unread'">{{ row.read ? '已读' : '未读' }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="sms_date" label="时间" width="160" />
        <el-table-column label="操作" width="72" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="danger" link @click="removeOne(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty
            :description="
              deviceId
                ? '该设备暂无短信存档（先在「命令下发」页下发「获取短信」命令）'
                : '请先选择设备'
            "
            :image-size="80"
          />
        </template>
      </el-table>

      <div class="more">
        <el-button
          v-if="items.length > 0 && items.length < total"
          :loading="loading"
          @click="loadMore"
        >
          加载更早的（已显示 {{ items.length }}/{{ total }}）
        </el-button>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import DevicePicker from '@/components/DevicePicker.vue'
import { listDevices } from '@/api/devices'
import { listSms, deleteSms, type SmsItem } from '@/api/data'
import type { Device } from '@/types'

/**
 * 短信页：按设备查看短信存档。
 * 分页用时间游标（before_ms = 已加载最旧一条的 sms_date），配合累积存档
 * 比页码翻页稳定（新数据插入不影响游标）。
 */

const TYPE_NAMES: Record<number, string> = {
  1: '收件箱',
  2: '已发送',
  3: '草稿',
  4: '发送中',
  5: '发送失败',
  6: '队列',
}

const PAGE_SIZE = 100

const devices = ref<Device[]>([])
const deviceId = ref('')
const smsType = ref<number | undefined>(undefined)
const keyword = ref('')
const items = ref<SmsItem[]>([])
const total = ref(0)
const loading = ref(false)

onMounted(async () => {
  devices.value = await listDevices()
})

function reload() {
  items.value = []
  loadPage()
}

async function loadPage() {
  if (!deviceId.value) return
  loading.value = true
  try {
    const oldest = items.value.length ? items.value[items.value.length - 1] : null
    const beforeMs = oldest ? Date.parse(oldest.sms_date.replace(' ', 'T')) : undefined
    const r = await listSms({
      device_id: deviceId.value,
      sms_type: smsType.value,
      keyword: keyword.value.trim() || undefined,
      before_ms: beforeMs,
      limit: PAGE_SIZE,
    })
    items.value.push(...r.items)
    total.value = r.total
  } finally {
    loading.value = false
  }
}

function loadMore() {
  loadPage()
}

async function removeOne(row: SmsItem) {
  try {
    await ElMessageBox.confirm(`删除 ${row.sms_date} 来自 ${row.address} 的这条存档？`, '提示', {
      type: 'warning',
    })
  } catch {
    return
  }
  const { deleted } = await deleteSms(deviceId.value, row.id)
  ElMessage.success(`已删除 ${deleted} 条`)
  reload()
}

function tagType(t: number): 'primary' | 'success' | 'warning' | 'danger' | 'info' {
  if (t === 1) return 'primary'
  if (t === 2) return 'success'
  if (t === 5) return 'danger'
  if (t === 3 || t === 4 || t === 6) return 'warning'
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
.summary {
  font-size: 12px;
  color: #909399;
}
.body-text {
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  line-height: 1.6;
}
.dim {
  color: #c0c4cc;
}
.unread {
  color: #e6a23c;
  font-weight: 600;
}
.more {
  margin-top: 12px;
  display: flex;
  justify-content: center;
}
</style>
