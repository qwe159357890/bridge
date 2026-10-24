<template>
  <div>
    <el-card shadow="never" class="page-card">
      <template #header>
        <div class="card-header">
          <span class="title">消息推送</span>
        </div>
      </template>
      <el-form label-width="100px" label-position="left">
        <el-form-item label="目标设备">
          <el-select
            v-model="form.deviceIds"
            multiple
            filterable
            collapse-tags
            collapse-tags-tooltip
            placeholder="选择设备（可多选）"
            style="width: 100%"
          >
            <el-option
              v-for="d in devices"
              :key="d.device_id"
              :label="`${d.device_name}（${d.device_id}）`"
              :value="d.device_id"
              :disabled="!d.online"
            >
              <span class="dot" :class="d.online ? 'dot-online' : 'dot-offline'" />
              <span>{{ d.device_name }}</span>
              <span class="opt-id">（{{ d.device_id }}）</span>
            </el-option>
          </el-select>
        </el-form-item>
        <el-form-item label="消息内容">
          <el-input
            v-model="form.payloadText"
            type="textarea"
            :rows="6"
            placeholder='JSON 格式，例如 {"title": "测试", "body": "你好"}'
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" :icon="Promotion" :loading="sending" @click="handlePush">
            推送消息
          </el-button>
          <el-button @click="form.deviceIds = []">清空选择</el-button>
        </el-form-item>
      </el-form>
      <el-alert
        type="info"
        :closable="false"
        title="消息通过 WebSocket 下发给设备端（message 帧），payload 为自定义 JSON，客户端业务层自行解析。"
      />
    </el-card>

    <el-card shadow="never" v-if="history.length > 0">
      <template #header>
        <div class="card-header">
          <span class="title">推送统计（本次会话累计）</span>
          <el-button size="small" text type="primary" @click="resetStats">重置统计</el-button>
        </div>
      </template>
      <el-descriptions :column="4" border>
        <el-descriptions-item label="累计推送次数">
          <span class="stat-number">{{ history.length }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="累计目标数">
          <span class="stat-number">{{ totalTargets }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="累计送达">
          <span class="stat-number success-num">{{ totalDelivered }}</span>
        </el-descriptions-item>
        <el-descriptions-item label="累计失败">
          <span class="stat-number fail-num">{{ totalFailed }}</span>
        </el-descriptions-item>
      </el-descriptions>

      <div class="history-list" v-if="history.length > 0">
        <div class="section-title">推送历史：</div>
        <el-table :data="history" size="small" stripe>
          <el-table-column label="时间" width="180">
            <template #default="{ row }">{{ row.time }}</template>
          </el-table-column>
          <el-table-column prop="deviceIds" label="目标设备" min-width="200">
            <template #default="{ row }">
              <el-tag
                v-for="id in row.deviceIds"
                :key="id"
                size="small"
                class="history-device"
                type="info"
                effect="plain"
              >
                {{ deviceNameOf(id) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="结果" width="160">
            <template #default="{ row }">
              <el-tag :type="row.failed.length === 0 ? 'success' : 'warning'" size="small">
                送达 {{ row.delivered }}/{{ row.total }}
              </el-tag>
            </template>
          </el-table-column>
        </el-table>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Promotion } from '@element-plus/icons-vue'
import { listDevices } from '@/api/devices'
import { pushMessage, type PushResult } from '@/api/push'
import type { Device } from '@/types'

interface PushHistoryItem {
  time: string
  deviceIds: string[]
  total: number
  delivered: number
  failed: string[]
}

const devices = ref<Device[]>([])
const sending = ref(false)
const history = ref<PushHistoryItem[]>([])

const form = reactive({
  deviceIds: [] as string[],
  payloadText: '{"title": "测试消息", "body": "你好"}',
})

const totalTargets = computed(() => history.value.reduce((s, h) => s + h.total, 0))
const totalDelivered = computed(() => history.value.reduce((s, h) => s + h.delivered, 0))
const totalFailed = computed(() => history.value.reduce((s, h) => s + h.failed.length, 0))

function deviceNameOf(id: string) {
  const d = devices.value.find((x) => x.device_id === id)
  return d ? d.device_name : id
}

async function load() {
  devices.value = await listDevices()
}

async function handlePush() {
  if (form.deviceIds.length === 0) {
    ElMessage.warning('请选择目标设备')
    return
  }
  let payload: Record<string, any>
  try {
    payload = JSON.parse(form.payloadText)
  } catch {
    ElMessage.error('消息内容不是合法的 JSON')
    return
  }
  sending.value = true
  try {
    const r: PushResult = await pushMessage(form.deviceIds, payload)
    // 追加到历史，实现会话内累计
    history.value.unshift({
      time: new Date().toLocaleString('zh-CN'),
      deviceIds: [...form.deviceIds],
      total: r.total,
      delivered: r.delivered,
      failed: r.failed,
    })
    ElMessage.success(`推送完成：送达 ${r.delivered}/${r.total} 台`)
  } catch {
    // 错误已统一提示
  } finally {
    sending.value = false
  }
}

function resetStats() {
  history.value = []
}

onMounted(load)
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.opt-id {
  color: #909399;
  font-size: 12px;
}
.history-list {
  margin-top: 16px;
}
.section-title {
  font-weight: 600;
  margin-bottom: 8px;
}
.history-device {
  margin-right: 6px;
}
.success-num {
  color: var(--success);
}
.fail-num {
  color: var(--danger);
}
</style>
