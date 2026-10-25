<template>
  <el-dialog
    :model-value="visible"
    :title="`位置历史 - ${deviceName}`"
    width="860px"
    destroy-on-close
    @update:model-value="(v: boolean) => emit('update:visible', v)"
    @open="onOpen"
  >
    <!-- 地图轨迹（Key 未配置时降级为纯列表） -->
    <div class="map-wrap">
      <div v-show="mapReady" ref="mapEl" class="map"></div>
      <el-alert
        v-if="mapError"
        :title="mapError"
        type="warning"
        :closable="false"
        show-icon
        class="map-fallback"
      />
    </div>

    <div class="toolbar">
      <span class="summary">
        共 {{ total }} 条记录（静止未移动的上报已合并，report_count 为该点累计上报次数）
      </span>
      <el-button
        size="small"
        type="danger"
        plain
        :disabled="records.length === 0"
        @click="clearAll"
      >
        清空该设备
      </el-button>
    </div>

    <el-table :data="records" v-loading="loading" stripe size="small" max-height="320">
      <el-table-column label="最新定位时间" width="160">
        <template #default="{ row }">{{ row.last_fix_time }}</template>
      </el-table-column>
      <el-table-column label="坐标 (WGS-84)" min-width="200" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.latitude.toFixed(6) }}, {{ row.longitude.toFixed(6) }}
        </template>
      </el-table-column>
      <el-table-column label="来源" width="150" show-overflow-tooltip>
        <template #default="{ row }">{{ row.provider || '-' }}</template>
      </el-table-column>
      <el-table-column label="误差" width="90">
        <template #default="{ row }">±{{ Math.round(row.accuracy) }}m</template>
      </el-table-column>
      <el-table-column prop="report_count" label="次数" width="70" />
      <el-table-column label="首次时间" width="160">
        <template #default="{ row }">{{ row.first_fix_time }}</template>
      </el-table-column>
      <el-table-column label="操作" width="80" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="danger" link @click="removeOne(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无位置记录（设备需升级到支持上报的 APK 版本后触发获取位置）" :image-size="60" />
      </template>
    </el-table>
  </el-dialog>
</template>

<script setup lang="ts">
import { nextTick, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { listLocations, deleteLocations, type LocationRecord } from '@/api/locations'
import { loadAmap } from '@/utils/amap'

const props = defineProps<{
  visible: boolean
  deviceId: string
  deviceName: string
}>()

const emit = defineEmits<{
  (e: 'update:visible', v: boolean): void
}>()

const records = ref<LocationRecord[]>([])
const total = ref(0)
const loading = ref(false)
const mapEl = ref<HTMLElement>()
const mapReady = ref(false)
const mapError = ref('')
let mapObj: any = null
let overlayGroup: any = null

async function onOpen() {
  await load()
  renderMap()
}

async function load() {
  loading.value = true
  try {
    const res = await listLocations({ device_id: props.deviceId, limit: 500 })
    records.value = res.locations
    total.value = res.total
  } finally {
    loading.value = false
  }
}

async function renderMap() {
  if (records.value.length === 0) return
  try {
    const AMap = await loadAmap()
    await nextTick()
    if (!mapObj) {
      mapObj = new AMap.Map(mapEl.value, { zoom: 12 })
      mapReady.value = true
    }
    if (overlayGroup) {
      mapObj.remove(overlayGroup)
      overlayGroup = null
    }
    const overlays: any[] = []
    const points = records.value
      .filter((r) => r.gcj_latitude != null && r.gcj_longitude != null)
      .map((r) => new AMap.LngLat(r.gcj_longitude, r.gcj_latitude))
    if (points.length === 0) return
    // 轨迹线（多点时）+ 各点标记
    if (points.length > 1) {
      overlays.push(new AMap.Polyline({ path: points, strokeColor: '#409EFF', strokeWeight: 4, strokeOpacity: 0.8 }))
    }
    for (let i = 0; i < points.length; i++) {
      const marker = new AMap.Marker({ position: points[i], title: records.value[i].last_fix_time })
      overlays.push(marker)
    }
    overlayGroup = overlays
    mapObj.add(overlays)
    mapObj.setFitView(overlays, false, [40, 40, 40, 40])
  } catch (e: any) {
    mapError.value = `地图加载失败：${e?.message || e}（不影响下方列表）`
  }
}

async function removeOne(row: LocationRecord) {
  try {
    await ElMessageBox.confirm(`删除 ${row.last_fix_time} 的这条位置记录？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  const { deleted } = await deleteLocations({ id: row.id })
  ElMessage.success(`已删除 ${deleted} 条`)
  await load()
  renderMap()
}

async function clearAll() {
  try {
    await ElMessageBox.confirm(
      `确定清空设备「${props.deviceName}」的全部 ${total.value} 条位置记录？此操作不可恢复。`,
      '危险操作',
      { type: 'warning', confirmButtonText: '清空' },
    )
  } catch {
    return
  }
  const { deleted } = await deleteLocations({ device_id: props.deviceId })
  ElMessage.success(`已清空 ${deleted} 条`)
  if (overlayGroup && mapObj) {
    mapObj.remove(overlayGroup)
    overlayGroup = null
  }
  await load()
}
</script>

<style scoped>
.map-wrap {
  margin-bottom: 12px;
}
.map {
  width: 100%;
  height: 320px;
  border-radius: 6px;
}
.map-fallback {
  margin-bottom: 4px;
}
.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
}
.summary {
  font-size: 12px;
  color: #909399;
}
</style>
