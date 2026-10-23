<template>
  <div>
    <el-row :gutter="16">
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-value">{{ health.active_connections }}</div>
          <div class="stat-label">在线设备数</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-value">{{ health.registered_devices }}</div>
          <div class="stat-label">已注册设备</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-value">{{ onlineRate }}%</div>
          <div class="stat-label">在线率</div>
        </el-card>
      </el-col>
      <el-col :span="6">
        <el-card class="stat-card">
          <div class="stat-value">{{ uptimeText }}</div>
          <div class="stat-label">服务运行时长</div>
        </el-card>
      </el-col>
    </el-row>

    <el-card class="detail-card">
      <template #header>系统状态</template>
      <el-descriptions :column="2" border>
        <el-descriptions-item label="服务状态">
          <el-tag :type="health.status === 'ok' ? 'success' : 'danger'" size="small">
            {{ health.status }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="运行时长（秒）">
          {{ health.uptime_seconds }}
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

const uptimeText = computed(() => {
  const s = health.value.uptime_seconds
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  return h > 0 ? `${h}时${m}分` : `${m}分${Math.floor(s % 60)}秒`
})

async function load() {
  health.value = await getHealth()
}

onMounted(load)
</script>

<style scoped>
.stat-card {
  text-align: center;
}
.stat-value {
  font-size: 32px;
  font-weight: 600;
  color: #1f3a8a;
}
.stat-label {
  margin-top: 8px;
  color: #909399;
  font-size: 13px;
}
.detail-card {
  margin-top: 16px;
}
</style>
