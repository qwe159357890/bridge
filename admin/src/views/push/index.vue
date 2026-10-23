<template>
  <div>
    <el-card>
      <template #header>消息推送</template>
      <el-form label-width="100px">
        <el-form-item label="目标设备">
          <el-select
            v-model="form.deviceIds"
            multiple
            filterable
            placeholder="选择设备（可多选）"
            style="width: 100%"
          >
            <el-option
              v-for="d in devices"
              :key="d.device_id"
              :label="`${d.device_name}（${d.device_id}）${d.online ? '' : ' · 离线'}`"
              :value="d.device_id"
              :disabled="!d.online"
            />
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
          <el-button type="primary" :loading="sending" @click="handlePush">
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

    <el-card class="result-card" v-if="result">
      <template #header>推送结果</template>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="目标数">{{ result.total }}</el-descriptions-item>
        <el-descriptions-item label="送达">{{ result.delivered }}</el-descriptions-item>
        <el-descriptions-item label="失败">{{ result.failed.length }}</el-descriptions-item>
      </el-descriptions>
      <div v-if="result.failed.length" class="failed-list">
        <div class="section-title">失败设备：</div>
        <el-tag v-for="id in result.failed" :key="id" class="failed-tag" type="danger">
          {{ id }}
        </el-tag>
      </div>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { listDevices } from '@/api/devices'
import { pushMessage, type PushResult } from '@/api/push'
import type { Device } from '@/types'

const devices = ref<Device[]>([])
const sending = ref(false)
const result = ref<PushResult | null>(null)

const form = reactive({
  deviceIds: [] as string[],
  payloadText: '{"title": "测试消息", "body": "你好"}',
})

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
    result.value = await pushMessage(form.deviceIds, payload)
    ElMessage.success(`推送完成：送达 ${result.value.delivered} 台`)
  } catch {
    // 错误已统一提示
  } finally {
    sending.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.result-card {
  margin-top: 16px;
}
.failed-list {
  margin-top: 16px;
}
.section-title {
  font-weight: 600;
  margin-bottom: 8px;
}
.failed-tag {
  margin-right: 8px;
}
</style>
