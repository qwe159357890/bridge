<template>
  <el-card v-if="visible" class="loc-card">
    <template #header>
      <div class="loc-header">
        <span class="title">📍 定位结果{{ deviceName ? ` - ${deviceName}` : '' }}</span>
        <div class="loc-header-right">
          <span v-if="status === 'ok'" class="loc-time">{{ record?.last_fix_time }}</span>
          <el-button :icon="Close" size="small" circle title="关闭" @click="hide" />
        </div>
      </div>
    </template>

    <!-- 等待设备上报（回执与入库是两条通道，入库可能晚到几百毫秒） -->
    <div v-if="status === 'waiting'" class="loc-waiting">
      <el-icon class="is-loading"><Loading /></el-icon>
      <span>命令已执行成功，等待设备上报位置…</span>
    </div>

    <el-alert
      v-else-if="status === 'timeout'"
      type="warning"
      :closable="false"
      show-icon
      title="未等到新的位置上报"
      description="设备可能未升级到支持上报的 APK 版本，或位置上报失败（不影响回执文本）；可重新下发。"
    />

    <!-- 地图：标记 + 误差圈 + 信息窗（地址/坐标/来源/误差/时间） -->
    <div v-show="status === 'ok'" ref="mapEl" class="loc-map"></div>
    <div v-if="status === 'ok' && record" class="loc-info">
      <span class="loc-address">地址：{{ address || '解析中…' }}</span>
      <span>坐标：{{ record.latitude.toFixed(6) }}, {{ record.longitude.toFixed(6) }}</span>
      <span>来源：{{ record.provider || '-' }}</span>
      <span>误差：±{{ Math.round(record.accuracy) }}m</span>
      <span v-if="record.report_count > 1">该点累计上报 {{ record.report_count }} 次</span>
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { nextTick, onBeforeUnmount, ref, watch } from 'vue'
import { Close, Loading } from '@element-plus/icons-vue'
import { latestLocation, reverseGeocode, type LocationRecord } from '@/api/locations'
import { loadAmap } from '@/utils/amap'

/**
 * 定位结果卡片（命令下发页用）。
 *
 * 下发 get_location 成功回执后调用 [show]：短轮询 /api/locations/latest
 * 等「本次下发之后」的新记录入库，然后在地图上以「标记 + 误差圈 + 信息窗」
 * 展示本次定位（信息窗含逆地理编码地址，借鉴 gps_aggregator_dialog 的
 * popup 信息布局与 fit 视野思路）。上报与回执解耦，等不到就明示超时。
 */

const visible = ref(false)
const status = ref<'waiting' | 'ok' | 'timeout'>('waiting')
const record = ref<LocationRecord | null>(null)
const address = ref('')
const deviceName = ref('')
const mapEl = ref<HTMLElement>()

let mapObj: any = null
let marker: any = null
let circle: any = null
let infoWindow: any = null
/** 轮询令牌：hide/重新 show 后使旧轮询失效 */
let pollToken = 0

defineExpose({ show, hide })

// 地址异步解析完成后刷新信息窗（信息窗内容在地图渲染时就固定了）
watch(address, () => {
  if (infoWindow && mapObj && record.value && status.value === 'ok') {
    infoWindow.setContent(buildInfoContent())
    if (infoWindow.getPosition()) {
      infoWindow.open(mapObj, infoWindow.getPosition())
    }
  }
})

/**
 * 展示卡片并开始等待本次定位入库。
 *
 * @param deviceId      目标设备
 * @param name          设备昵称（标题展示）
 * @param sentAtMs      本次下发的本地时刻（毫秒），用于判断记录是否属于本次
 */
async function show(deviceId: string, name: string, sentAtMs: number) {
  deviceName.value = name
  visible.value = true
  status.value = 'waiting'
  record.value = null
  address.value = ''
  const token = ++pollToken

  // 短轮询最多 ~8 秒：上报是 fire-and-forget，正常 1 秒内入库
  for (let i = 0; i < 7; i++) {
    await sleep(1100)
    if (token !== pollToken) return
    try {
      const r = await latestLocation(deviceId)
      const loc = r.location
      if (loc && isFresh(loc, sentAtMs)) {
        record.value = loc
        status.value = 'ok'
        await nextTick()
        renderMap(loc)
        resolveAddress(loc)
        return
      }
    } catch {
      // 服务端暂时不可用：继续重试直至超时
    }
  }
  status.value = 'timeout'
}

function hide() {
  visible.value = false
  pollToken++ // 使进行中的轮询失效
}

/** 记录是否属于本次下发（server_time 晚于下发时刻，容忍 1 分钟时钟偏差）。 */
function isFresh(loc: LocationRecord, sentAtMs: number): boolean {
  const t = Date.parse(loc.server_time.replace(' ', 'T'))
  if (Number.isNaN(t)) return true // 解析失败时宁滥勿缺，至少能看到最近位置
  return t >= sentAtMs - 60_000
}

function sleep(ms: number) {
  return new Promise<void>((r) => setTimeout(r, ms))
}

/** 渲染地图：定位标记 + 误差圈 + 信息窗，视野自适应（参考实现思路）。 */
async function renderMap(loc: LocationRecord) {
  try {
    const AMap = await loadAmap()
    await nextTick()
    if (!mapObj) {
      mapObj = new AMap.Map(mapEl.value, { zoom: 15 })
    }
    const center = new AMap.LngLat(
      loc.gcj_longitude ?? loc.longitude,
      loc.gcj_latitude ?? loc.latitude,
    )
    const old = [marker, circle].filter(Boolean)
    if (old.length) mapObj.remove(old)

    marker = new AMap.Marker({ position: center, title: '本次定位' })
    // 误差圈：半径取回执误差（下限 30m 保证肉眼可见），样式与轨迹蓝一致
    circle = new AMap.Circle({
      center,
      radius: Math.max(loc.accuracy || 0, 30),
      strokeColor: '#409EFF',
      strokeWeight: 1,
      strokeOpacity: 0.6,
      fillColor: '#409EFF',
      fillOpacity: 0.15,
    })
    mapObj.add([circle, marker])
    mapObj.setZoomAndCenter(15, center)

    if (!infoWindow) infoWindow = new AMap.InfoWindow({ offset: new AMap.Pixel(0, -34) })
    infoWindow.setContent(buildInfoContent())
    infoWindow.open(mapObj, center)
  } catch {
    // 地图加载失败不阻断：下方文本信息仍可见
  }
}

/** 信息窗内容（借鉴参考实现的 popup 布局）。 */
function buildInfoContent(): string {
  const r = record.value
  if (!r) return ''
  const rows = [
    `<div class="loc-iw-name">📍 本次定位</div>`,
    address.value ? `<div>地址：${escapeHtml(address.value)}</div>` : '',
    `<div>坐标：${r.latitude.toFixed(6)}, ${r.longitude.toFixed(6)}</div>`,
    `<div>来源：${r.provider || '-'}　误差：±${Math.round(r.accuracy)}m</div>`,
    `<div>时间：${r.last_fix_time}</div>`,
  ]
  return `<div class="loc-iw">${rows.filter(Boolean).join('')}</div>`
}

/**
 * 逆地理编码：走服务端 /api/locations/address（高德 Web服务 REST）。
 * 不用浏览器 JS API Geocoder 插件——新 Key 平台限制下插件行为不可控，
 * 服务端 REST 可实测且带 24h 缓存。
 */
async function resolveAddress(loc: LocationRecord) {
  address.value = ''
  try {
    const r = await reverseGeocode(loc.latitude, loc.longitude)
    address.value = r.address || '未配置 Web服务 Key，无法解析地址'
  } catch (e: any) {
    address.value = `解析失败（${e?.message || '接口异常'}）`
  }
}

function escapeHtml(s: string): string {
  return s.replace(/[&<>"']/g, (c) => `&#${c.charCodeAt(0)};`)
}

onBeforeUnmount(() => {
  pollToken++
  mapObj?.destroy()
  mapObj = null
})
</script>

<style scoped>
.loc-card {
  margin-top: 16px;
}
.loc-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.loc-header .title {
  font-weight: 600;
}
.loc-header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.loc-time {
  font-size: 12px;
  color: #909399;
}
.loc-waiting {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  padding: 48px 0;
  color: #909399;
  font-size: 13px;
}
.loc-map {
  width: 100%;
  height: 360px;
  border-radius: 6px;
}
.loc-info {
  display: flex;
  flex-wrap: wrap;
  gap: 6px 18px;
  font-size: 12px;
  color: #606266;
  margin-top: 8px;
}
.loc-address {
  flex-basis: 100%;
  color: #303133;
}
</style>

<style>
/* 信息窗内容样式（AMap InfoWindow 渲染在 body 下，需全局样式） */
.loc-iw {
  font-size: 12px;
  line-height: 1.7;
  color: #303133;
  min-width: 200px;
}
.loc-iw .loc-iw-name {
  font-weight: 600;
  margin-bottom: 2px;
}
</style>
