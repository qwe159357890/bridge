<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="6">
        <el-card class="stat-card" shadow="hover">
          <div class="stat-value" style="color: #409eff">{{ health.active_connections }}</div>
          <div class="stat-label">在线设备数</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card" shadow="hover">
          <div class="stat-value" style="color: #67c23a">{{ health.registered_devices }}</div>
          <div class="stat-label">已注册设备</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card" shadow="hover">
          <div class="stat-value" style="color: #e6a23c">{{ onlineRate }}%</div>
          <div class="stat-label">在线率</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card" shadow="hover">
          <div class="stat-value" style="color: #7c6ff0">{{ uptimeText }}</div>
          <div class="stat-label">服务运行时长</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="detail-card" shadow="never">
      <template #header>
        <div class="card-header"><span class="title">系统状态</span></div>
      </template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="服务状态">
          <span class="dot" :class="health.status === 'ok' ? 'dot-online' : 'dot-disconnected'" />
          {{ health.status === 'ok' ? '正常' : health.status }}
        </el-descriptions-item>
        <el-descriptions-item label="运行时长">
          {{ uptimeText }}
        </el-descriptions-item>
        <el-descriptions-item label="在线设备数">
          {{ health.active_connections }}
        </el-descriptions-item>
        <el-descriptions-item label="已注册设备">
          {{ health.registered_devices }}
        </el-descriptions-item>
      </el-descriptions>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { getHealth } from '@/api/health'
import { formatDuration } from '@/utils/format'
import type { HealthInfo } from '@/types'

const health = ref<HealthInfo>({
  status: 'unknown',
  active_connections: 0,
  registered_devices: 0,
  uptime_seconds: 0,
})

const onlineRate = computed(() => {
  if (health.value.registered_devices === 0) return 0
  return Math.round(
    (health.value.active_connections / health.value.registered_devices) * 100,
  )
})

const uptimeText = computed(() => formatDuration(health.value.uptime_seconds))

async function load() {
  health.value = await getHealth()
}

onMounted(load)
</script>

<style scoped>
.stat-card {
  text-align: center;
  border-radius: 8px;
}
.stat-value {
  font-size: 30px;
  font-weight: 600;
  line-height: 1.2;
}
.stat-label {
  margin-top: 8px;
  color: #909399;
  font-size: 13px;
}
.detail-card {
  margin-top: 16px;
  border-radius: 8px;
}
</style>
