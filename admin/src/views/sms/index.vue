<template>
  <div>
    <el-card>
      <div class="toolbar">
        <div class="left">
          <DevicePicker v-model="deviceId" :devices="devices" placeholder="选择设备" with-all />
          <el-select v-model="smsType" style="width: 160px" @change="reload">
            <el-option label="全部" :value="undefined" />
            <el-option v-for="(n, t) in TYPE_NAMES" :key="t" :label="n" :value="Number(t)" />
          </el-select>
          <el-input
            v-model="keyword"
            placeholder="搜索号码 / 正文"
            clearable
            style="width: 260px"
            @keyup.enter="reload"
            @clear="reload"
          />
          <el-button type="primary" @click="reload">查询</el-button>
          <el-tooltip content="向设备下发「获取短信」命令拉取最新短信入库，完成后自动刷新列表" placement="top">
            <span>
              <el-button
                type="success"
                :loading="refreshing"
                :disabled="!deviceId || refreshing"
                @click="refreshFromDevice"
              >
                {{ refreshing ? '拉取中…' : '刷新设备短信' }}
              </el-button>
            </span>
          </el-tooltip>
        </div>
        <div class="right">
          <el-button
            size="small"
            :disabled="selected.length === 0"
            @click="markSelected(true)"
          >
            标记已读（{{ selected.length }}）
          </el-button>
          <el-button
            size="small"
            :disabled="selected.length === 0"
            @click="markSelected(false)"
          >
            标记未读（{{ selected.length }}）
          </el-button>
          <span class="summary">共 {{ total }} 条（累积存档，重复下发自动去重）</span>
        </div>
      </div>

      <el-table
        :data="items"
        v-loading="loading"
        stripe
        size="small"
        max-height="560"
        @selection-change="onSelectionChange"
      >
        <el-table-column type="selection" width="42" />
        <el-table-column label="设备" width="130" show-overflow-tooltip>
          <template #default="{ row }">
            <span>{{ deviceNameOf(row.device_id) }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag :type="tagType(row.sms_type)" size="small">{{ row.sms_type_name }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="address" label="号码" width="150" show-overflow-tooltip />
        <el-table-column label="所属卡" width="150">
          <template #default="{ row }">
            <span v-if="row.sim_slot >= 0">{{ simLabel(row) }}</span>
            <span v-else class="dim">未知（旧数据，重新下发采集可回填）</span>
          </template>
        </el-table-column>
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
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              size="small"
              :type="row.read ? 'warning' : 'success'"
              link
              @click="markOne(row)"
            >
              {{ row.read ? '标为未读' : '标为已读' }}
            </el-button>
            <el-button size="small" type="danger" link @click="removeOne(row)">删除</el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty
            :description="
              deviceId
                ? '该设备暂无短信存档（点击上方「刷新设备短信」从设备拉取）'
                : '暂无任何设备的短信存档（选中设备后点「刷新设备短信」拉取）'
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
import { onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import DevicePicker from '@/components/DevicePicker.vue'
import { listDevices } from '@/api/devices'
import { sendCommand, getCommand } from '@/api/commands'
import { listSms, deleteSms, setSmsRead, simLabel, type SmsItem } from '@/api/data'
import type { Device } from '@/types'
import { useTargetDeviceStore } from '@/stores/targetDevice'

/**
 * 短信页：短信存档查询（2026-10-28 起默认聚合模式——显示所有设备的短信，
 * 设备选择器可选「全部设备（聚合）」或具体设备；每行显示来源设备）。
 * 分页用时间游标（before_ms = 已加载最旧一条的 sms_date），配合累积存档
 * 比页码翻页稳定（新数据插入不影响游标）。
 * 已读状态双向可标记（已读/未读），仅更新存档；设备端同步另发 mark_sms_read 命令。
 * 「刷新设备短信」= 直接下发 get_sms 命令（需先选中具体设备），回执后自动重查。
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
// 短信页默认聚合（''），但用户**主动选了具体设备**时写回全局目标设备——
// 其它页面（命令下发/通话记录/通讯录）随之保持；聚合视图不清全局
const targetStore = useTargetDeviceStore()
watch(deviceId, (v) => {
  if (v) targetStore.set(v)
})
const smsType = ref<number | undefined>(undefined)
const keyword = ref('')
const items = ref<SmsItem[]>([])
const total = ref(0)
const loading = ref(false)

onMounted(async () => {
  devices.value = await listDevices()
  loadPage() // 进页即聚合视图（deviceId 为空 = 所有设备）
})

/** 设备显示名（聚合列用）。 */
function deviceNameOf(id: string) {
  return devices.value.find((d) => d.device_id === id)?.device_name || id
}

function reload() {
  items.value = []
  loadPage()
}

async function loadPage() {
  loading.value = true
  try {
    const oldest = items.value.length ? items.value[items.value.length - 1] : null
    const beforeMs = oldest ? Date.parse(oldest.sms_date.replace(' ', 'T')) : undefined
    const r = await listSms({
      // 为空 = 聚合模式（所有设备）
      device_id: deviceId.value || undefined,
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

// ---------------- 刷新设备短信（直发 get_sms 命令 + 自动重查） ----------------

const refreshing = ref(false)

const sleep = (ms: number) => new Promise<void>((r) => setTimeout(r, ms))

/** 下发 get_sms（收件箱为主 + 已发送，增量去重入库），轮询回执后自动刷新列表。 */
async function refreshFromDevice() {
  if (!deviceId.value || refreshing.value) return
  refreshing.value = true
  try {
    const r = await sendCommand(deviceId.value, 'get_sms', { sms_type: 'all', limit: 200 }, true)
    if (!r.delivered) {
      ElMessage.warning('设备不在线，无法拉取短信（设备连上后再试）')
      return
    }
    ElMessage.info('已下发采集命令，等待设备回执…')
    const deadline = Date.now() + 90_000
    while (Date.now() < deadline) {
      await sleep(1500)
      const c = await getCommand(r.command_id)
      if (c.status === 'success') {
        ElMessage.success('设备短信已拉取入库')
        reload()
        return
      }
      if (c.status === 'failed') {
        ElMessage.error(c.result_message || '设备拉取短信失败')
        return
      }
    }
    ElMessage.warning('命令 90 秒未回执，稍后请手动点「查询」查看')
  } finally {
    refreshing.value = false
  }
}

// ---------------- 标记已读/未读（双向） ----------------

const selected = ref<SmsItem[]>([])

function onSelectionChange(rows: SmsItem[]) {
  selected.value = rows
}

/** 按行所属设备分组标记已读/未读（聚合模式下选中行可能跨设备）。 */
async function markRows(rows: SmsItem[], read: boolean, tip: string) {
  if (rows.length === 0) return
  const byDevice = new Map<string, number[]>()
  for (const r of rows) {
    const list = byDevice.get(r.device_id) || []
    list.push(r.id)
    byDevice.set(r.device_id, list)
  }
  let updated = 0
  for (const [dev, ids] of byDevice) {
    const { updated: n } = await setSmsRead(dev, ids, read)
    updated += n
  }
  ElMessage.success(`${tip} ${updated} 条`)
  reload()
}

function markOne(row: SmsItem) {
  markRows([row], !row.read, row.read ? '已标为未读' : '已标为已读')
}

function markSelected(read: boolean) {
  markRows(selected.value, read, read ? '已标为已读' : '已标为未读')
}

async function removeOne(row: SmsItem) {
  try {
    await ElMessageBox.confirm(`删除 ${row.sms_date} 来自 ${row.address} 的这条存档？`, '提示', {
      type: 'warning',
    })
  } catch {
    return
  }
  const { deleted } = await deleteSms(row.device_id, row.id)
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
.right {
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
