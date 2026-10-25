<template>
  <el-dialog
    :model-value="visible"
    :title="`位置历史 - ${deviceName}`"
    :width="dialogWidth + 'px'"
    class="location-dialog"
    destroy-on-close
    @update:model-value="(v: boolean) => emit('update:visible', v)"
    @open="onOpen"
    @closed="onClosed"
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
        共 {{ total }} 条记录；设备未移动的重复上报会合并为一个点，「次数」列即该点累计上报次数
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
      <el-table-column label="时间" min-width="180">
        <template #default="{ row }">
          <div>{{ row.last_fix_time }}</div>
          <div
            v-if="row.report_count > 1 && row.first_fix_time !== row.last_fix_time"
            class="sub-time"
            :title="`首次定位：${row.first_fix_time}`"
          >
            首次 {{ row.first_fix_time }}
          </div>
        </template>
      </el-table-column>
      <el-table-column label="坐标 (WGS-84)" min-width="190" show-overflow-tooltip>
        <template #default="{ row }">
          {{ row.latitude.toFixed(6) }}, {{ row.longitude.toFixed(6) }}
        </template>
      </el-table-column>
      <el-table-column label="来源" min-width="130" show-overflow-tooltip>
        <template #default="{ row }">{{ row.provider || '-' }}</template>
      </el-table-column>
      <el-table-column label="误差" width="80">
        <template #default="{ row }">±{{ Math.round(row.accuracy) }}m</template>
      </el-table-column>
      <el-table-column prop="report_count" label="次数" width="64" />
      <el-table-column label="操作" width="72" fixed="right">
        <template #default="{ row }">
          <el-button size="small" type="danger" link @click="removeOne(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="暂无位置记录（设备需升级到支持上报的 APK 版本后触发获取位置）" :image-size="60" />
      </template>
    </el-table>

    <!-- 右下角拖拽手柄：按住可调整弹窗宽度 -->
    <div class="resize-handle" title="拖动调整宽度" @mousedown="startResize"></div>
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
/** 创建 mapObj 时挂载的容器：destroy-on-close 后 DOM 会换新，须据此判断是否要重建 */
let mapHost: HTMLElement | null = null
let overlayGroup: any = null
/** 悬停详情信息窗（地图标记共用一个，随 mouseover 切换内容） */
let infoWin: any = null

// 弹窗宽度：默认自适应（大屏封顶 1280），右下角手柄可拖拽调整
const dialogWidth = ref(Math.min(window.innerWidth - 48, 1280))

function startResize(e: MouseEvent) {
  e.preventDefault()
  const startX = e.clientX
  const startW = dialogWidth.value
  const onMove = (ev: MouseEvent) => {
    dialogWidth.value = Math.min(
      Math.max(startW + (ev.clientX - startX), 560),
      window.innerWidth - 24,
    )
  }
  const onUp = () => {
    window.removeEventListener('mousemove', onMove)
    window.removeEventListener('mouseup', onUp)
    mapObj?.resize?.() // 拖宽后让地图自适应新容器尺寸
  }
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseup', onUp)
}

async function onOpen() {
  await load()
  renderMap()
}

/** 弹窗关闭动画结束：destroy-on-close 已销毁 DOM，同步丢弃地图实例与覆盖物引用。 */
function onClosed() {
  mapObj?.destroy()
  mapObj = null
  mapHost = null
  overlayGroup = null
  infoWin = null
  mapReady.value = false
}

/** 标记悬停详情 HTML（与表格列信息对齐；InfoWindow 渲染在 body 下，样式走全局）。 */
function markerInfoHtml(r: LocationRecord): string {
  const esc = (s: unknown) =>
    String(s).replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`)
  const merged = r.report_count > 1 && r.first_fix_time !== r.last_fix_time
  const rows = [
    `<div class="miw-title">📍 定位点</div>`,
    `<div>最新定位：${esc(r.last_fix_time)}</div>`,
    merged ? `<div>首次定位：${esc(r.first_fix_time)}</div>` : '',
    `<div>坐标：${r.latitude.toFixed(6)}, ${r.longitude.toFixed(6)}</div>`,
    `<div>来源：${esc(r.provider || '-')}　误差：±${Math.round(r.accuracy)}m</div>`,
    r.report_count > 1
      ? `<div>累计上报：<b>${r.report_count}</b> 次</div>`
      : `<div>累计上报：1 次</div>`,
  ]
  return `<div class="miw">${rows.filter(Boolean).join('')}</div>`
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
    // 先显示容器再创建地图：display:none 时容器尺寸为 0，高德会渲染成白屏
    mapReady.value = true
    await nextTick()
    if (!mapEl.value) return
    // destroy-on-close 会让 DOM 重挂载，容器换了就必须重建地图实例
    if (mapObj && mapHost !== mapEl.value) {
      mapObj.destroy()
      mapObj = null
      overlayGroup = null
    }
    if (!mapObj) {
      mapObj = new AMap.Map(mapEl.value, { zoom: 12, resizeEnable: true })
      mapHost = mapEl.value
    }
    if (overlayGroup) {
      mapObj.remove(overlayGroup)
      overlayGroup = null
    }
    const overlays: any[] = []
    // (record, 坐标) 成对构建，避免索引错位；悬停标记弹出详情信息窗
    const pairs = records.value
      .filter((r) => r.gcj_latitude != null && r.gcj_longitude != null)
      .map((r) => ({ record: r, point: new AMap.LngLat(r.gcj_longitude, r.gcj_latitude) }))
    if (pairs.length === 0) return
    // 轨迹线（多点时）
    if (pairs.length > 1) {
      overlays.push(
        new AMap.Polyline({
          path: pairs.map((p) => p.point),
          strokeColor: '#409EFF',
          strokeWeight: 4,
          strokeOpacity: 0.8,
        }),
      )
    }
    if (!infoWin) infoWin = new AMap.InfoWindow({ offset: new AMap.Pixel(0, -32) })
    for (const { record, point } of pairs) {
      const marker = new AMap.Marker({ position: point, title: record.last_fix_time })
      // 悬停弹出详情（与表格列对齐：时间/坐标/来源/误差/次数）
      marker.on('mouseover', () => {
        infoWin.setContent(markerInfoHtml(record))
        infoWin.open(mapObj, point)
      })
      marker.on('mouseout', () => infoWin.close())
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
.sub-time {
  font-size: 11px;
  color: #909399;
  margin-top: 2px;
  white-space: nowrap;
}
</style>

<style>
/* 弹窗拖拽手柄（el-dialog teleport 到 body，scoped 样式够不到，需全局） */
.location-dialog .el-dialog {
  position: relative;
}
.location-dialog .resize-handle {
  position: absolute;
  right: 2px;
  bottom: 2px;
  width: 18px;
  height: 18px;
  cursor: nwse-resize;
  z-index: 10;
}
.location-dialog .resize-handle::before {
  content: '';
  position: absolute;
  right: 4px;
  bottom: 4px;
  width: 9px;
  height: 9px;
  border-right: 2px solid #c0c4cc;
  border-bottom: 2px solid #c0c4cc;
  border-radius: 1px;
}
.location-dialog .resize-handle:hover::before {
  border-color: #409eff;
}
/* 标记悬停详情信息窗（AMap InfoWindow 渲染在 body 下，scoped 够不到） */
.miw {
  font-size: 12px;
  line-height: 1.7;
  color: #303133;
  min-width: 190px;
  white-space: nowrap;
}
.miw .miw-title {
  font-weight: 600;
  margin-bottom: 2px;
}
.miw b {
  color: #409eff;
}
</style>
