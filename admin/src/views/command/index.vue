<template>
  <div>
    <el-card class="command-card-root">
      <template #header>
        <div class="top-bar">
          <span class="title">下发命令</span>
          <div class="top-bar-right">
            <span class="device-label">目标设备</span>
            <DevicePicker
              v-model="form.deviceId"
              :devices="devices"
              only-online
              placeholder="选择在线设备"
              style="width: 240px"
            />
            <el-tag
              v-if="form.deviceId && musicState"
              :type="musicTagType"
              size="small"
              class="music-tag"
            >
              {{ musicStateText }}
            </el-tag>
            <el-button
              v-if="form.deviceId"
              :icon="Refresh"
              size="small"
              circle
              title="刷新播放状态"
              @click="loadMusicState"
            />
          </div>
        </div>
      </template>

      <!-- 主体：左分类导航 + 右命令卡片墙 -->
      <div class="command-picker">
        <!-- 左：分类导航（占满左列高度） -->
        <div class="category-pane">
          <div class="category-title">命令分类</div>
          <div
            v-for="cat in categories"
            :key="cat.key"
            class="category-item"
            :class="{ active: activeCategory === cat.key }"
            @click="selectCategory(cat.key)"
          >
            <span class="cat-icon">{{ cat.icon }}</span>
            <span class="cat-label">{{ cat.label }}</span>
            <span class="cat-count">{{ countOf(cat.key) }}</span>
          </div>
        </div>

        <!-- 右：命令卡片墙（主角） -->
        <div class="command-pane">
          <div class="command-toolbar">
            <el-input
              v-model="keyword"
              placeholder="搜索命令名称 / 说明…"
              :prefix-icon="Search"
              clearable
              style="width: 280px"
            />
            <span class="pane-title">{{ activeCategoryLabel }} · {{ filteredCommands.length }} 个命令</span>
          </div>

          <div v-if="filteredCommands.length === 0" class="empty-commands">
            该分类下暂无命令
          </div>
          <div v-else class="command-grid">
            <!-- 描述悬停显示（卡片只留图标+名称，避免长说明挤爆卡片） -->
            <el-tooltip
              v-for="cmd in filteredCommands"
              :key="cmd.name"
              :content="cmd.description"
              placement="top"
              :show-after="250"
              popper-class="cmd-desc-tip"
            >
              <div
                class="command-card"
                :class="{ active: selectedCommand?.name === cmd.name, blocked: isBlocked(cmd) }"
                @click="selectCommand(cmd)"
              >
                <div class="command-icon">{{ cmd.icon }}</div>
                <div class="command-name">{{ cmd.label }}</div>
              </div>
            </el-tooltip>
          </div>

          <!-- 参数 + 下发按钮（右区底部） -->
          <div v-if="selectedCommand" class="param-area">
            <div class="param-header">
              <span class="selected-command-hint">
                {{ selectedCommand.icon }} {{ selectedCommand.label }}
                <span class="command-key">({{ selectedCommand.name }})</span>
              </span>
            </div>

            <div v-if="selectedCommand.params.length === 0" class="no-params">
              该命令无需参数，可直接下发。
            </div>
            <el-form v-else label-width="110px" class="param-form">
              <div
                v-if="selectedCommand.name === 'set_volume' || selectedCommand.name === 'get_volume'"
                class="sim-hint"
                :class="{ 'sim-hint-empty': !volumeSnapshot }"
              >
                <template v-if="volumeSnapshot">
                  <span style="margin-right: 8px">设备当前音量：</span>
                  <span
                    v-for="s in volumeSnapshot.streams"
                    :key="s.key"
                    style="margin-right: 10px"
                  >{{ s.name }} <b>{{ s.current }}/{{ s.max }}</b>（{{ s.pct }}%）</span>
                  <span style="margin-right: 10px; color: var(--el-text-color-secondary)">
                    铃声模式：{{ volumeSnapshot.ringer || '未知' }}
                  </span>
                  <span
                    v-if="volumeSnapshot.dnd"
                    style="color: var(--el-color-warning)"
                  >免打扰开启</span>
                  <el-button type="primary" link size="small" @click="refreshVolumeSnapshot">
                    刷新
                  </el-button>
                </template>
                <template v-else>正在查询设备当前音量…</template>
              </div>
              <div
                v-if="selectedCommand.params.some((p) => p.name === 'sim')"
                class="sim-hint"
                :class="{ 'sim-hint-empty': deviceSims.length === 0 }"
              >
                <template v-if="deviceSims.length > 0">
                  当前设备 {{ deviceSims.length }} 张卡：{{
                    deviceSims
                      .map((s) => {
                        const slot = `卡${s.slot_index + 1}`
                        const parts = [s.number || '号码未知', s.carrier].filter(Boolean)
                        return `${slot}（${parts.join(' · ')}）`
                      })
                      .join('、')
                  }}{{ simSyncTime ? `，采集于 ${simSyncTime}` : '' }}
                </template>
                <template v-else>
                  尚未获取该设备的卡信息，下方选卡为通用选项；可先下发「📱 获取SIM卡信息」命令获取实际卡列表
                </template>
              </div>
              <!-- 流式模式：时长参数隐藏（0=不限时自动处理），在表单顶部说明 -->
              <div v-if="isStreamMode" class="stream-mode-hint">
                ⏱ 流式模式不限时：下发后一直传输，点 <b>⏹ 停止采集</b> 结束。
              </div>
              <el-form-item
                v-for="p in visibleParams"
                :key="p.name"
                :label="p.label"
                :required="p.required"
              >
                <el-input
                  v-if="p.type === 'text'"
                  v-model="params[p.name]"
                  :placeholder="p.placeholder || ''"
                  style="width: 320px"
                />
                <el-input-number
                  v-else-if="p.type === 'number'"
                  v-model="params[p.name]"
                  :placeholder="p.placeholder || ''"
                  style="width: 320px"
                />
                <el-switch
                  v-else-if="p.type === 'switch'"
                  v-model="params[p.name]"
                />
                <el-select
                  v-else-if="p.type === 'select'"
                  v-model="params[p.name]"
                  :placeholder="p.placeholder || '请选择'"
                  style="width: 320px"
                >
                  <el-option
                    v-for="o in p.name === 'sim' ? simOptions : p.options || []"
                    :key="o.value"
                    :label="o.label"
                    :value="o.value"
                  />
                </el-select>
                <span
                  v-if="p.name === 'stream' && volumeSnapshot"
                  class="stream-current"
                >当前：{{ volumeCurrentText(volumeSnapshot, params.stream) }}</span>
                <el-input
                  v-else-if="p.type === 'textarea'"
                  v-model="params[p.name]"
                  type="textarea"
                  :rows="2"
                  :placeholder="p.placeholder || ''"
                  style="width: 320px"
                />
                <div v-if="p.description" class="param-desc">{{ p.description }}</div>
              </el-form-item>
            </el-form>

            <div class="send-bar">
              <el-button
                type="primary"
                size="large"
                :loading="sending"
                :disabled="!canSend || commandBlocked"
                @click="handleSend"
              >
                下发命令
              </el-button>
              <el-button
                v-if="isStreamMode && form.deviceId"
                type="warning"
                size="large"
                :disabled="!streamActive"
                @click="stopStreamCapture"
              >
                ⏹ 停止采集
              </el-button>
              <el-tooltip
                v-if="showForceSend"
                content="开启后忽略播放状态限制，音乐控制命令直接下发，由设备按真实状态执行（状态不匹配会回执失败原因）"
                placement="top"
              >
                <el-checkbox v-model="forceSend" class="force-send">强制下发</el-checkbox>
              </el-tooltip>
              <span v-if="!form.deviceId" class="send-hint">请先选择在线设备</span>
              <span v-else-if="blockHint" class="send-hint warn">{{ blockHint }}</span>
            </div>
          </div>

          <div v-else class="param-placeholder">
            选择一张命令卡片，查看参数并下发
          </div>
        </div>
      </div>
    </el-card>

    <!-- 定位结果：get_location 回执成功后自动显示（地图 + 误差圈 + 地址） -->
    <LocationResultCard ref="locationCard" />

    <!-- 音量控制：get_volume 回执成功后自动显示（5 条滑条，拖动反向下发调节） -->
    <VolumeResultCard ref="volumeCard" />

    <!-- 流式采集实时播放：stream_status 事件驱动（视频 mpegts.js / 录音 Web Audio 实时出声） -->
    <StreamPlayerCard ref="streamCard" :device-names="deviceNameMap" @request-stop="stopActiveCapture" />

    <el-card class="list-card">
      <template #header>
        <div class="card-header">
          <span class="title">命令历史</span>
          <div>
            <span class="ws-status">
              <span class="dot" :class="wsDotClass" />
              {{ wsStatusText }}
            </span>
            <el-button type="primary" :icon="Refresh" size="small" @click="load">
              刷新
            </el-button>
            <el-button type="danger" size="small" @click="clearHistory">清空历史</el-button>
          </div>
        </div>
      </template>

      <el-table :data="commands" v-loading="loading" stripe>
        <el-table-column prop="command_id" label="命令 ID" width="200" show-overflow-tooltip />
        <el-table-column label="设备" min-width="140">
          <template #default="{ row }">
            {{ deviceNameOf(row.device_id) }}
          </template>
        </el-table-column>
        <el-table-column label="命令" width="160">
          <template #default="{ row }">
            {{ commandLabelOf(row.command) }}
          </template>
        </el-table-column>
        <el-table-column label="状态" width="150">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">
              {{ statusText(row.status) }}
            </el-tag>
            <!-- 耗时命令两阶段进度（执行中/上传中）：SSE command_progress 实时驱动，回执后消失 -->
            <div v-if="progressTextOf(row)" class="progress-line">{{ progressTextOf(row) }}</div>
          </template>
        </el-table-column>
        <el-table-column label="回执信息" min-width="220">
          <template #default="{ row }">
            <!-- 布局要点：不能用 show-overflow-tooltip（整格裁切会把「查看」按钮
                 一起裁没），文字自行省略、按钮 flex-shrink:0 恒可见 -->
            <div class="receipt-cell">
              <!-- 文字超宽时悬停看全文（不能用 show-overflow-tooltip：整格裁切会连「查看」一起裁没） -->
              <el-tooltip
                :content="row.result_message || '-'"
                placement="top"
                :show-after="300"
                :disabled="!(row.result_message && row.result_message.length > 24)"
              >
                <span class="receipt-text">{{ row.result_message || '-' }}</span>
              </el-tooltip>
              <el-button
                v-if="mediaFilenameOf(row)"
                size="small"
                type="primary"
                link
                @click="openMediaPreview(row)"
              >
                查看
              </el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 命令产物预览（截屏/拍照/录屏/录像/录音结果内嵌查看，免去切换到媒体文件页） -->
    <el-dialog v-model="previewVisible" :title="previewTitle" width="760px" destroy-on-close>
      <div v-loading="previewLoading" class="media-preview">
        <video
          v-if="previewIsVideo && previewSrc"
          :src="previewSrc"
          controls
          autoplay
          class="media-preview-body"
        />
        <audio
          v-else-if="previewIsAudio && previewSrc"
          :src="previewSrc"
          controls
          autoplay
          style="width: 100%; margin-top: 40px"
        />
        <img v-else-if="previewSrc" :src="previewSrc" class="media-preview-body" />
      </div>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { onBeforeRouteLeave } from 'vue-router'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { deviceCount, getMusicState, listDevices } from '@/api/devices'
import type { MusicState } from '@/api/devices'
import { clearCommands, listCommands, sendCommand, getCommandCatalog } from '@/api/commands'
import { listMedia, mediaFileUrl } from '@/api/media'
import type { MediaItem } from '@/api/media'
import { getDeviceSims } from '@/api/data'
import type { DeviceSim } from '@/api/data'
import { getToken } from '@/api/request'
import { wsUrl } from '@/utils/base'
import { useTargetDeviceStore } from '@/stores/targetDevice'
import { formatTime } from '@/utils/format'
import DevicePicker from '@/components/DevicePicker.vue'
import LocationResultCard from '@/components/LocationResultCard.vue'
import VolumeResultCard from '@/components/VolumeResultCard.vue'
import { parseVolumeMessage, volumeCurrentText, type VolumeSnapshot } from '@/utils/volume'
import StreamPlayerCard from '@/components/StreamPlayerCard.vue'
import type {
  CommandCategory,
  CommandDef,
  CommandRecord,
  Device,
} from '@/types'

const route = useRoute()
const devices = ref<Device[]>([])
const commands = ref<CommandRecord[]>([])
const loading = ref(false)
const sending = ref(false)

// ---------------------------------------------------------------------------
// 命令产物内嵌预览（v0.1.41）：截屏/拍照/录屏回执里点「查看」直接弹窗预览，
// 不用再切到媒体文件页找。文件名从回执消息提取（上传回执格式固定含 filename），
// 按 device_id + filename 在媒体列表里反查记录 id，再拼带 token 的文件 URL。
// ---------------------------------------------------------------------------
// 媒体文件名两种形态：
// ① 服务端重命名（上传落盘后的正式名，回执消息里就是这个）：20261026_210709_549381_screen_record.mp4
// ② 客户端原始名（老回执兜底）：screen_1729980000000.mp4
const MEDIA_FILE_RE = /((?:\d{8}_\d{6}_\d+_(?:screenshot|photo|screen_record|video|audio)|(?:screenshot|photo|screen|video|audio)_\d+)\.(?:jpg|mp4|m4a))/

function mediaFilenameOf(row: CommandRecord): string | null {
  if (row.status !== 'success' || !row.result_message) return null
  return row.result_message.match(MEDIA_FILE_RE)?.[1] ?? null
}

const previewVisible = ref(false)
const previewLoading = ref(false)
const previewSrc = ref('')
const previewIsVideo = ref(false)
const previewIsAudio = ref(false)
const previewTitle = ref('')

async function openMediaPreview(row: CommandRecord) {
  const filename = mediaFilenameOf(row)
  if (!filename) return
  previewTitle.value = `命令产物预览 — ${filename}`
  previewVisible.value = true
  previewLoading.value = true
  previewSrc.value = ''
  try {
    const { items }: { items: MediaItem[] } = await listMedia(200)
    const hit = items.find((it) => it.device_id === row.device_id && it.filename === filename)
    if (!hit) {
      previewVisible.value = false
      ElMessage.warning(`媒体文件 ${filename} 不在列表中（可能已被自动清理）`)
      return
    }
    previewIsVideo.value = filename.endsWith('.mp4')
    previewIsAudio.value = filename.endsWith('.m4a')
    previewSrc.value = mediaFileUrl(hit.id)
  } catch (e) {
    previewVisible.value = false
    ElMessage.error(`加载媒体信息失败：${e instanceof Error ? e.message : String(e)}`)
  } finally {
    previewLoading.value = false
  }
}

// 命令目录（从后端动态拉取）
const categories = ref<CommandCategory[]>([])
const commandDefs = ref<CommandDef[]>([])
const catalogLoading = ref(false)

// 选中状态
const activeCategory = ref('')
const keyword = ref('')
const selectedCommand = ref<CommandDef | null>(null)
const params = reactive<Record<string, any>>({})

// WS 连接状态：connecting / connected / disconnected / reconnecting
const wsState = ref<'connecting' | 'connected' | 'disconnected' | 'reconnecting'>('connecting')
let ws: WebSocket | null = null
let reconnectTimer: number | null = null
// 连续连接失败次数与退避间隔：失败后先探测 token 有效性（401 由 axios
// 拦截器清 token 跳登录），token 有效才继续指数退避重连（3s 起步翻倍，
// 30s 封顶，±20% 抖动——token 已持久化到数据库，服务端重启不再失效）
let wsFailCount = 0
let reconnectDelay = 3000
const RECONNECT_MAX_DELAY = 30000

const wsStatusText = computed(() => {
  switch (wsState.value) {
    case 'connecting':
      return '连接中'
    case 'connected':
      return '实时连接中'
    case 'reconnecting':
      return '重连中'
    default:
      return '实时已断开'
  }
})
const wsDotClass = computed(() => {
  switch (wsState.value) {
    case 'connected':
      return 'dot-online'
    case 'connecting':
    case 'reconnecting':
      return 'dot-connecting'
    default:
      return 'dot-disconnected'
  }
})

const targetStore = useTargetDeviceStore()
const form = reactive({
  // 目标设备接全局 store（跨页面保持，刷新不丢）；深链 query 优先
  deviceId: (route.query.deviceId as string) || targetStore.deviceId || '',
})
// 用户在命令页切换设备时写回全局（其它页面随之保持）
watch(
  () => form.deviceId,
  (v) => {
    if (v) targetStore.set(v)
  },
)

// 当前分类下的命令（未搜索时）
const categoryCommands = computed(() => {
  if (!activeCategory.value) return commandDefs.value
  return commandDefs.value.filter((c) => c.category === activeCategory.value)
})

// 搜索过滤后的命令
const filteredCommands = computed(() => {
  const kw = keyword.value.trim().toLowerCase()
  if (!kw) return categoryCommands.value
  return categoryCommands.value.filter(
    (c) =>
      c.label.toLowerCase().includes(kw) ||
      c.name.toLowerCase().includes(kw) ||
      c.description.toLowerCase().includes(kw),
  )
})

const canSend = computed(() => !!form.deviceId && !!selectedCommand.value)

// ---------------------------------------------------------------------------
// 采集类命令互斥守卫（与 server commands.py CAPTURE_COMMANDS 对齐）：
// 摄像头/麦克风/投屏是独占硬件，同设备上一条采集命令未回执时禁止再发采集命令
// ---------------------------------------------------------------------------
const CAPTURE_COMMANDS = ['screenshot', 'record_screen', 'take_photo', 'record_video', 'record_audio']
const CAPTURE_GUARD_WINDOW_MS = 15 * 60 * 1000

/** 目标设备在途的采集命令（15 分钟窗口内的 pending，视为正在执行；超窗视为僵尸不拦）。 */
const pendingCapture = computed<CommandRecord | null>(() => {
  if (!form.deviceId) return null
  const now = Date.now()
  return (
    commands.value.find(
      (r) =>
        r.device_id === form.deviceId &&
        r.status === 'pending' &&
        CAPTURE_COMMANDS.includes(r.command) &&
        now - r.created_at * 1000 <= CAPTURE_GUARD_WINDOW_MS,
    ) ?? null
  )
})

// ---------------------------------------------------------------------------
// 音乐播放状态：目标设备的实时状态徽标 + 控制类命令按钮联动
// ---------------------------------------------------------------------------

const musicState = ref<MusicState | null>(null)

// 目标设备的 SIM 卡信息（get_sim_info 上报的快照）：选卡下拉按它动态渲染
const deviceSims = ref<DeviceSim[]>([])
const simSyncTime = ref('')

/** sim 参数动态选项：有设备卡信息按实际卡（号码/运营商），否则退化通用选项。 */
const simOptions = computed<{ value: string; label: string }[]>(() => {
  const base = [{ value: 'auto', label: '自动（系统默认卡）' }]
  if (deviceSims.value.length === 0) {
    return [
      ...base,
      { value: 'sim1', label: '卡 1' },
      { value: 'sim2', label: '卡 2' },
      { value: 'sim3', label: '卡 3' },
    ]
  }
  return [
    ...base,
    ...deviceSims.value.map((s) => {
      const slot = `卡${s.slot_index + 1}`
      const parts = [s.number || '号码未知', s.carrier].filter(Boolean)
      return { value: `sim${s.slot_index + 1}`, label: `${slot}（${parts.join(' · ')}）` }
    }),
  ]
})

async function loadDeviceSims() {
  if (!form.deviceId) {
    deviceSims.value = []
    simSyncTime.value = ''
    return
  }
  try {
    const r = await getDeviceSims(form.deviceId)
    deviceSims.value = r.items
    simSyncTime.value = r.sync_time || ''
  } catch {
    deviceSims.value = []
    simSyncTime.value = ''
  }
}

// 定位结果卡片：下发 get_location 后记录 command_id，回执推送按 ID 匹配触发地图展示
const locationCard = ref<any>(null)
let pendingLocationCommandId = ''
let locationSentAt = 0

// 音量卡片：get_volume 回执（成功且目标设备匹配）流入即解析刷新滑条
const volumeCard = ref<any>(null)

// 音量实时快照（参数区联动）：get_volume 回执解析后存这里，
// 供「调节音量/获取音量」参数区显示设备当前音量（顶部条 + stream 下拉旁）
const volumeSnapshot = ref<VolumeSnapshot | null>(null)
let volumeQueryKey = '' // 已自动查询过的 设备:命令 组合（防重复下发）

/** 选中音量类命令 + 已选设备时自动下发一次 get_volume 拉实时快照。 */
const volumeAutoKey = computed(() => {
  const cmd = selectedCommand.value?.name
  const isVol = cmd === 'set_volume' || cmd === 'get_volume'
  return isVol && form.deviceId ? `${form.deviceId}:${cmd}` : ''
})

watch(volumeAutoKey, (key) => {
  if (!key || key === volumeQueryKey) return
  volumeQueryKey = key
  refreshVolumeSnapshot()
})

/** 下发 get_volume 拉当前音量快照（回执经 WS 流入 onVolumeResult）。 */
function refreshVolumeSnapshot() {
  if (!form.deviceId) return
  sendCommand(form.deviceId, 'get_volume', {}).catch(() => {})
}

// 切换「音量类型」下拉时，自动把「音量值」带入该流当前百分比（可手动修改）
watch(
  () => params.stream,
  (stream) => {
    if (selectedCommand.value?.name !== 'set_volume') return
    const s = volumeSnapshot.value?.streams.find((x) => x.key === stream)
    if (s) params.level = s.pct
  },
)

// 流式播放卡片：stream_status 起止事件驱动（分片走组件自己的专用流 WS）
const streamCard = ref<any>(null)

// 流式采集命令 → 播放通道类型（下发成功即预连，见 doSend 内注释）
const STREAM_COMMAND_KINDS: Record<string, 'video' | 'audio' | 'screen'> = {
  record_video: 'video',
  record_audio: 'audio',
  screen_live: 'screen',
}

/**
 * 流式模式（2026-10-27 用户拍板）：screen_live 天生流式；record_video/record_audio
 * 勾选「流式模式」后进入。流式不限时——时长参数隐藏（duration=0 自动下发），
 * 用「⏹ 停止采集」按钮结束。
 */
const isStreamMode = computed(() => {
  const cmd = selectedCommand.value
  if (!cmd) return false
  if (cmd.name === 'screen_live') return true
  return (cmd.name === 'record_video' || cmd.name === 'record_audio') && !!params.stream
})

/** 表单可见参数：流式模式下隐藏时长（duration=0 由 collectParams 自动下发）。 */
const visibleParams = computed(() => {
  const cmd = selectedCommand.value
  if (!cmd) return []
  if (!isStreamMode.value) return cmd.params
  return cmd.params.filter((p) => p.name !== 'duration')
})

/** 停止当前设备的流式采集（流式模式专用按钮，等价下发 stream_stop）。 */
async function stopStreamCapture() {
  if (!form.deviceId) return
  try {
    const r = await sendCommand(form.deviceId, 'stream_stop', {})
    ElMessage.success(`停止命令已下发（${r.command_id.slice(0, 8)}…）`)
    // 立即切断实时卡片的全部流 WS 订阅（不等设备 is_end 帧），并封锁
    // silentRefresh 的自动重启——用户明确要求「不要保留僵尸订阅」
    streamCard.value?.onManualStop()
    await load()
  } catch {
    /* 失败提示已由拦截器统一弹出 */
  }
}

/**
 * 当前是否正在采集（⏹ 停止采集按钮的可用依据，2026-10-28 用户拍板：
 * 只有正在采集时按钮才可用）。两个信号取或：
 * ① 实时卡片在播；② 选中设备有 15 分钟窗口内的流式命令在途
 * （卡片可能已被切走销毁，但设备端采集还在跑，此时仍应允许停止）。
 */
const streamActive = computed(() => {
  if (streamCard.value?.isStreaming) return true
  const now = Date.now()
  return commands.value.some((r) => {
    const k = STREAM_COMMAND_KINDS[r.command]
    if (!k || r.status !== 'pending') return false
    if (r.device_id !== form.deviceId) return false
    const isStream = k === 'screen' || !!r.params?.stream
    return isStream && now - r.created_at * 1000 <= CAPTURE_GUARD_WINDOW_MS
  })
})
const deviceNameMap = computed<Record<string, string>>(() => {
  const map: Record<string, string> = {}
  for (const d of devices.value) map[d.device_id] = d.device_name
  return map
})

// 命令两阶段进度（SSE command_progress 实时驱动，不落库）：command_id → 最近进度。
// 仅「待回执」状态的命令展示；最终回执到达即删除对应条目。
interface CommandProgress {
  phase: 'executing' | 'uploading'
  percent?: number | null
  sent_bytes?: number | null
  total_bytes?: number | null
}
const progressMap = ref<Record<string, CommandProgress>>({})

/** 「待回执」命令的进度副文案：执行中 XX% / 上传中 XX%（8.2/18.2MB）；无进度返回 null。 */
function progressTextOf(row: { status: string; command_id: string }): string | null {
  if (row.status === 'success' || row.status === 'failed') return null
  const p = progressMap.value[row.command_id]
  if (!p) return null
  if (p.phase === 'uploading') {
    const sent = p.sent_bytes ?? 0
    const total = p.total_bytes ?? 0
    const fmt = (n: number) => (n >= 1048576 ? `${(n / 1048576).toFixed(1)}MB` : `${Math.round(n / 1024)}KB`)
    const pct = p.percent ?? (total > 0 ? Math.round((sent * 100) / total) : 0)
    return `上传中 ${pct}%（${fmt(sent)}/${fmt(total)}）`
  }
  return `执行中 ${p.percent ?? 0}%`
}

/** 控制类命令允许下发的设备状态（播放音乐不受限，设备在线即可）。 */
const MUSIC_STATE_RULES: Record<string, string[]> = {
  pause_music: ['playing'],
  resume_music: ['paused'],
  stop_music: ['playing', 'paused'],
}

/** 强制下发：开启后绕过状态联动禁用，交由客户端按真实状态校验（状态不匹配回执失败）。 */
const forceSend = ref(false)

/**
 * 强制下发开关只对音乐控制命令显示（状态联动死锁的兜底）。
 * 约定（2026-10-25）：其余命令一律不显示，除非确有同类「状态联动可能死锁」场景。
 */
const showForceSend = computed(() => {
  const cmd = selectedCommand.value
  if (!cmd) return false
  if (MUSIC_STATE_RULES[cmd.name] !== undefined) return true
  // 采集互斥场景：目标设备有采集命令在途时，提供强制下发出口
  return CAPTURE_COMMANDS.includes(cmd.name) && !!pendingCapture.value
})

const musicStateText = computed(() => {
  const s = musicState.value
  if (!s) return ''
  switch (s.state) {
    case 'playing':
      return `▶ 正在播放：${s.source || '未知曲目'}`
    case 'paused':
      return `⏸ 已暂停：${s.source || '未知曲目'}`
    case 'idle':
      return '空闲（无播放）'
    default:
      return '状态未知'
  }
})

const musicTagType = computed(() => {
  switch (musicState.value?.state) {
    case 'playing':
      return 'success'
    case 'paused':
      return 'warning'
    default:
      return 'info'
  }
})

/** 命令当前是否被设备状态禁用（卡片置灰 + 下发按钮禁用）。强制模式下绕过状态限制。 */
function isBlocked(cmd: CommandDef): boolean {
  const allowed = MUSIC_STATE_RULES[cmd.name]
  if (!allowed) return false
  if (forceSend.value) return false
  if (!form.deviceId) return true
  const state = musicState.value?.state ?? 'unknown'
  return !allowed.includes(state)
}

const commandBlocked = computed(() => {
  const cmd = selectedCommand.value
  if (!cmd) return false
  if (isBlocked(cmd)) return true
  // 采集互斥：目标设备已有采集命令在途，禁用下发（勾选强制下发可越过）
  return CAPTURE_COMMANDS.includes(cmd.name) && !!pendingCapture.value && !forceSend.value
})

const blockHint = computed(() => {
  if (!selectedCommand.value) return ''
  // 采集互斥提示（优先于音乐状态提示，两类不重叠）
  if (CAPTURE_COMMANDS.includes(selectedCommand.value.name) && pendingCapture.value && !forceSend.value) {
    const mins = Math.max(1, Math.round((Date.now() - pendingCapture.value.created_at * 1000) / 60000))
    return `该设备正在执行「${pendingCapture.value.command}」（约 ${mins} 分钟前下发，未回执）——摄像头/麦克风/投屏互斥，并发下发会失败；确认设备无响应可勾选强制下发`
  }
  if (forceSend.value && CAPTURE_COMMANDS.includes(selectedCommand.value.name) && pendingCapture.value) {
    return '强制模式：命令将直接下发，若设备确实仍在采集，会由设备端互斥兜底回执失败'
  }
  if (!MUSIC_STATE_RULES[selectedCommand.value.name]) return ''
  if (forceSend.value) {
    return '强制模式：命令将直接下发，由设备按真实状态执行，状态不匹配会回执失败'
  }
  if (!commandBlocked.value) return ''
  const state = musicState.value?.state ?? 'unknown'
  if (state === 'unknown') return '设备离线或状态未知，无法下发；若确认设备在播放，可开启强制下发'
  switch (selectedCommand.value.name) {
    case 'pause_music':
      return '当前没有正在播放的音乐，无法暂停'
    case 'resume_music':
      return state === 'idle' ? '当前没有可恢复的音乐' : '正在播放中，无需恢复'
    case 'stop_music':
      return '当前没有正在播放的音乐，无法停止'
    default:
      return ''
  }
})

async function loadMusicState() {
  if (!form.deviceId) {
    musicState.value = null
    return
  }
  try {
    musicState.value = await getMusicState(form.deviceId)
  } catch {
    musicState.value = null
  }
}

watch(
  () => form.deviceId,
  () => {
    forceSend.value = false // 切换目标设备时重置强制下发，防止残留开启误操作
    pendingLocationCommandId = '' // 切换设备后旧的定位回执不再触发地图
    locationCard.value?.hide()
    volumeCard.value?.hide() // 音量卡片随设备切换关闭（新设备需重新获取）
    volumeSnapshot.value = null // 参数区实时音量同步失效（音量命令选中时自动重查）
    loadMusicState()
    loadDeviceSims() // 选卡下拉按新设备的实际卡动态渲染
  },
)

const activeCategoryLabel = computed(() => {
  const cat = categories.value.find((c) => c.key === activeCategory.value)
  return cat ? cat.label : '全部'
})

function countOf(catKey: string) {
  return commandDefs.value.filter((c) => c.category === catKey).length
}

function commandLabelOf(name: string) {
  const def = commandDefs.value.find((c) => c.name === name)
  return def ? `${def.icon} ${def.label}` : name
}

function selectCategory(key: string) {
  activeCategory.value = key
  keyword.value = ''
  // 切换分类时若已选命令不属于该分类，则保留原选中（用户可能只是想浏览）
}

/** 切换命令卡片 / 离开命令页时：若正在实时播放（投屏/流式录像/录音），
 *  立即**结束设备端采集**（2026-10-28 用户拍板，很重要——只断前端 WS 的话
 *  设备会继续采集上行）+ 断本地全部流 WS + 保留 15min 封锁防自动重启。 */
async function stopActiveCapture() {
  const card = streamCard.value
  if (!card?.isStreaming) return
  const dev: string = card.currentDeviceId
  card.onManualStop() // 同步：立即断本地全部流 WS（不等设备 is_end）
  if (dev) {
    try {
      await sendCommand(dev, 'stream_stop', {})
      ElMessage.success('切走时已自动停止设备端采集')
    } catch {
      /* 失败提示已由拦截器统一弹出 */
    }
  }
  card.hide(true) // 销毁控件并保留停止封锁（hide 默认会清封锁，此处要留）
}

function selectCommand(cmd: CommandDef) {
  selectedCommand.value = cmd
  // 切换命令卡片即销毁实时画面控件（2026-10-28 用户拍板）；若正在实时播放，
  // 先结束设备端采集（stream_stop）再销毁——只断前端 WS 设备会继续采。
  if (streamCard.value?.isStreaming) {
    void stopActiveCapture()
  } else {
    streamCard.value?.hide()
  }
  // 重置参数：按 default 或类型给初值
  for (const p of cmd.params) {
    if (p.default !== undefined && p.default !== null) {
      params[p.name] = p.default
    } else {
      params[p.name] = p.type === 'switch' ? false : ''
    }
  }
  // 参数持久化记忆（2026-10-28 用户需求）：用户上次对该命令「下发成功」时
  // 填写的参数自动回填，无记忆时保持 catalog 默认值
  try {
    const saved = localStorage.getItem(`bridge_cmd_params:${cmd.name}`)
    if (saved) {
      const memo = JSON.parse(saved) as Record<string, unknown>
      for (const p of cmd.params) {
        if (p.name in memo && memo[p.name] !== null) params[p.name] = memo[p.name]
      }
    }
  } catch {
    /* 记忆损坏时静默回退默认值 */
  }
}

/** 下发成功后按命令名持久化本次填写的参数（下次选中自动回填）。 */
function memorizeParams(cmdName: string) {
  const cmd = selectedCommand.value
  if (!cmd || cmd.name !== cmdName) return
  const memo: Record<string, unknown> = {}
  for (const p of cmd.params) memo[p.name] = params[p.name] ?? null
  try {
    localStorage.setItem(`bridge_cmd_params:${cmdName}`, JSON.stringify(memo))
  } catch {
    /* 存储满/隐私模式忽略 */
  }
}

function collectParams(): Record<string, any> {
  if (!selectedCommand.value) return {}
  const out: Record<string, any> = {}
  for (const p of selectedCommand.value.params) {
    const v = params[p.name]
    if (v !== undefined && v !== null && v !== '') {
      out[p.name] = v
    }
  }
  // 流式模式：时长固定 0（不限时），表单里已隐藏该参数
  if (isStreamMode.value) out.duration = 0
  return out
}

function deviceNameOf(id: string) {
  const d = devices.value.find((x) => x.device_id === id)
  return d ? d.device_name : id
}

function statusType(s: string) {
  if (s === 'success') return 'success'
  if (s === 'failed') return 'danger'
  return 'warning'
}

function statusText(s: string) {
  if (s === 'success') return '成功'
  if (s === 'failed') return '失败'
  return '待回执'
}

async function load() {
  loading.value = true
  try {
    commands.value = await listCommands()
  } finally {
    loading.value = false
  }
  ensureStreamPreconnect()
}

/**
 * 流式命令兜底预连（第三重保险）：发现选中设备的流式命令在途而卡片没在播
 * 同路流 → 主动预连。覆盖「下发时预连异常」「stream_status 事件丢失」等场景。
 * 在途 = 15 分钟窗口内的 pending（与服务端采集互斥守卫同窗，僵尸命令不拦）。
 */
function ensureStreamPreconnect() {
  const now = Date.now()
  const running = commands.value.find((r) => {
    const k = STREAM_COMMAND_KINDS[r.command]
    if (!k || r.status !== 'pending' || now - r.created_at * 1000 > CAPTURE_GUARD_WINDOW_MS)
      return false
    // 只兜底当前选中设备的命令：避免劫持正在播放的其他设备流卡片
    if (r.device_id !== form.deviceId) return false
    return k === 'screen' || !!r.params?.stream
  })
  if (running) {
    // source='auto'：用户切走/关掉的会话不会被自动弹回（组件内 userClosed 拦截）
    streamCard.value?.startManual(running.device_id, STREAM_COMMAND_KINDS[running.command], 'auto')
  }
}

/** 静默刷新命令历史 + 流式兜底（不触发 loading 态）。 */
async function silentRefresh() {
  if (document.hidden) return
  try {
    commands.value = await listCommands()
    ensureStreamPreconnect()
  } catch {
    /* 轮询失败忽略（网络恢复后自动续上） */
  }
}

/** 清空命令历史（二次确认；当前选中的设备存在时只清该设备，否则清空全部）。 */
async function clearHistory() {
  const deviceId = form.deviceId || ''
  const scope = deviceId ? `设备「${deviceNameOf(deviceId)}」的` : '全部'
  try {
    await ElMessageBox.confirm(
      `确定清空${scope}命令历史吗？此操作不可恢复。`,
      '清空历史',
      { confirmButtonText: '清空', cancelButtonText: '取消', type: 'warning' },
    )
  } catch {
    return
  }
  const r = await clearCommands(deviceId || undefined)
  ElMessage.success(`已清空 ${r.deleted} 条命令历史`)
  await load()
}

async function loadDevices() {
  devices.value = await listDevices()
}

async function loadCatalog() {
  catalogLoading.value = true
  try {
    const catalog = await getCommandCatalog()
    categories.value = catalog.categories
    commandDefs.value = catalog.commands
    // 默认选中第一个分类
    if (categories.value.length > 0) {
      activeCategory.value = categories.value[0].key
    }
  } finally {
    catalogLoading.value = false
  }
}

async function handleSend() {
  if (!form.deviceId) {
    ElMessage.warning('请选择目标设备')
    return
  }
  if (!selectedCommand.value) {
    ElMessage.warning('请选择命令')
    return
  }
  await doSend(forceSend.value)
}

/** 实际下发；force=true 越过服务端采集互斥守卫。409 时询问是否强制重发。 */
async function doSend(force: boolean) {
  const cmdName = selectedCommand.value!.name
  sending.value = true
  try {
    // 投屏低延迟开关：播放器卡片启动时读取（跨组件最轻的传参方式）
    if (cmdName === 'screen_live') {
      localStorage.setItem('bridge_stream_low_latency', params.low_latency ? '1' : '0')
    }
    const r = await sendCommand(form.deviceId, cmdName, collectParams(), force)
    memorizeParams(cmdName) // 下发成功即记忆本次参数（2026-10-28 用户需求）
    ElMessage.success(`命令已${force ? '强制' : ''}下发（${r.command_id.slice(0, 8)}…）`)
    // 流式采集命令：下发成功即预连实时播放通道（不等 stream_status 事件）。
    // 规避管理 WS 短暂断线/事件丢失导致整段录制无实时画面（2026-10-27 实测缺陷）
    const streamKind = STREAM_COMMAND_KINDS[cmdName]
    if (streamKind && form.deviceId) {
      const isStreamMode = streamKind === 'screen' || !!params.stream
      if (isStreamMode) streamCard.value?.startManual(form.deviceId, streamKind, 'manual')
    }
    // get_location：记下命令 ID 与下发时刻，等回执推送匹配后展示定位结果地图
    if (cmdName === 'get_location') {
      pendingLocationCommandId = r.command_id
      locationSentAt = Date.now()
    }
    load()
  } catch (e: any) {
    // 409 = 服务端采集互斥守卫拦截（错误详情已由拦截器统一弹出），询问是否强制下发
    if (e?.response?.status === 409 && CAPTURE_COMMANDS.includes(cmdName)) {
      try {
        await ElMessageBox.confirm(
          '设备可能仍在执行上一条采集命令（等待回执）。并发下发会因摄像头/麦克风/投屏互斥而失败，确定要强制下发吗？',
          '采集互斥提醒',
          { confirmButtonText: '强制下发', cancelButtonText: '取消', type: 'warning' },
        )
        await doSend(true)
      } catch {
        // 用户取消
      }
    }
  } finally {
    sending.value = false
  }
}

// 实时回执推送（WebSocket）
function connectWS() {
  const token = getToken()
  if (!token) return

  let url = wsUrl('/api/admin/ws')
  url += `${url.includes('?') ? '&' : '?'}token=${encodeURIComponent(token)}`

  wsState.value = 'connecting'
  ws = new WebSocket(url)

  ws.onopen = () => {
    wsState.value = 'connected'
    wsFailCount = 0
    reconnectDelay = 3000
  }
  ws.onmessage = (event) => {
    try {
      const msg = JSON.parse(event.data)
      if (msg.type === 'command_result') {
        load()
        // 回执到达：清除该命令的两阶段进度（历史只留最终结果）
        if (progressMap.value[msg.data.command_id]) {
          const m = { ...progressMap.value }
          delete m[msg.data.command_id]
          progressMap.value = m
        }
        // get_location 回执：成功则触发定位结果地图（按下发时记录的 command_id 精准匹配）
        if (msg.data.command_id === pendingLocationCommandId) {
          pendingLocationCommandId = ''
          if (msg.data.success && msg.data.device_id === form.deviceId) {
            locationCard.value?.show(
              msg.data.device_id,
              deviceNameOf(msg.data.device_id),
              locationSentAt,
            )
          }
        }
        const text = msg.data.success ? '命令执行成功' : '命令执行失败'
        ElMessage.success(`${msg.data.device_id}：${text}`)
        // get_sim_info 回执成功：刷新当前设备卡信息（选卡下拉动态更新）
        if (
          msg.data.command === 'get_sim_info' &&
          msg.data.success &&
          msg.data.device_id === form.deviceId
        ) {
          loadDeviceSims()
        }
        // get_volume 回执成功：弹出/刷新音量控制卡片（滑条可反向下发调节）
        if (
          msg.data.command === 'get_volume' &&
          msg.data.success &&
          msg.data.device_id === form.deviceId
        ) {
          volumeSnapshot.value = parseVolumeMessage(msg.data.message || '') // 参数区联动
          volumeCard.value?.show(
            msg.data.device_id,
            deviceNameOf(msg.data.device_id),
            msg.data.message || '',
          )
        }
      } else if (msg.type === 'stream_status') {
        // 流式采集起止：驱动实时播放卡片开/关（分片重负载不经过本通道）
        streamCard.value?.onStreamStatus(msg.data)
      } else if (msg.type === 'music_state') {
        // 实时音乐状态推送：仅更新当前目标设备
        if (msg.data.device_id === form.deviceId) {
          musicState.value = msg.data
        }
      } else if (msg.type === 'command_interaction') {
        // 弹窗点击回传等交互事件：刷新命令历史 + 轻提示
        load()
        ElMessage.success(`${deviceNameOf(msg.data.device_id)}：${msg.data.note}`)
      } else if (msg.type === 'command_progress') {
        // 命令两阶段进度（执行中/上传中）：更新进度条目，「待回执」状态列实时显示
        progressMap.value = { ...progressMap.value, [msg.data.command_id]: msg.data }
      }
    } catch {
      // 忽略解析错误
    }
  }
  ws.onclose = () => {
    wsState.value = 'disconnected'
    scheduleReconnect()
  }
  ws.onerror = () => {
    wsState.value = 'disconnected'
    ws?.close()
  }
}

function scheduleReconnect() {
  if (reconnectTimer) return
  wsState.value = 'reconnecting'
  wsFailCount += 1
  if (wsFailCount >= 2) {
    // 连续失败 ≥2 次：探测 token 是否还有效（走 axios 拦截器）。
    // 401 → 拦截器清 token + 跳登录，重连循环随之终止；网络错误 → 停在本次等待。
    deviceCount().catch(() => {
      /* 401 已跳登录；网络错误保持静默，靠下方退避节奏重试 */
    })
  }
  // 指数退避 + ±20% 随机抖动（3s → 30s 封顶）：服务端长时间不在线时
  // 不刷日志/不弹错误轰炸；抖动避免多标签页同时打开时同步踩踏重连
  const jitter = reconnectDelay * 0.8 + Math.random() * reconnectDelay * 0.4
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = null
    connectWS()
  }, Math.round(jitter))
  reconnectDelay = Math.min(reconnectDelay * 2, RECONNECT_MAX_DELAY)
}

let refreshTimer: number | null = null // 命令历史静默轮询（3s，兼流式兜底预连）

onMounted(() => {
  loadDevices()
  load()
  loadCatalog()
  connectWS()
  refreshTimer = window.setInterval(silentPoll, 3000)
})

/**
 * 轮询降频（v0.1.56）：无活跃会话时 15s 才真正拉一次命令列表——
 * 之前无条件 3s 轮询，服务器终端每 3s 刷一条 GET /api/commands（用户反馈刷屏）。
 * 活跃 = 有待回执命令 / 上传进度 / 实时卡片在播。
 */
let pollTick = 0
async function silentPoll() {
  pollTick++
  const active =
    streamCard.value?.isStreaming ||
    Object.keys(progressMap.value).length > 0 ||
    commands.value.some((r) => r.status === 'pending')
  if (!active && pollTick % 5 !== 0) return
  await silentRefresh()
}

onBeforeUnmount(() => {
  if (reconnectTimer) window.clearTimeout(reconnectTimer)
  reconnectTimer = null
  if (refreshTimer !== null) window.clearInterval(refreshTimer)
  refreshTimer = null
  ws?.close()
})

/** 切走选项卡（离开本页）时：若正在实时播放，先向设备下发 stream_stop 结束
 *  采集（异步不阻塞导航，请求已发出），再销毁卡片；未在播放则仅销毁。 */
onBeforeRouteLeave(() => {
  if (streamCard.value?.isStreaming) {
    void stopActiveCapture()
  } else {
    streamCard.value?.hide()
  }
})
</script>

<style scoped>
/* 回执信息格：文字省略 + 「查看」按钮恒可见（不能整格裁切） */
.receipt-cell {
  display: flex;
  align-items: center;
  gap: 4px;
}
.progress-line {
  margin-top: 2px;
  font-size: 12px;
  color: var(--el-color-warning);
  line-height: 1.2;
}
.receipt-text {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.receipt-cell .el-button {
  flex-shrink: 0;
}

/* 命令产物预览弹窗 */
.media-preview {
  min-height: 120px;
  display: flex;
  justify-content: center;
}
.media-preview-body {
  max-width: 100%;
  max-height: 60vh;
  border-radius: 6px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.list-card {
  margin-top: 16px;
}
.ws-status {
  display: inline-flex;
  align-items: center;
  font-size: 13px;
  color: #606266;
  margin-right: 12px;
}

/* 顶栏：标题 + 设备选择 */
.top-bar {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.top-bar-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.device-label {
  font-size: 13px;
  color: #606266;
}

/* 命令选择器：左分类导航 + 右卡片墙（命令主导） */
.command-picker {
  display: flex;
  border: 1px solid #e4e7ed;
  border-radius: 8px;
  overflow: hidden;
  min-height: 480px;
}
.category-pane {
  width: 190px;
  flex-shrink: 0;
  background: #f5f7fa;
  border-right: 1px solid #e4e7ed;
  padding: 12px 0;
}
.category-title {
  font-size: 12px;
  color: #909399;
  padding: 4px 16px 10px;
}
.category-item {
  display: flex;
  align-items: center;
  padding: 12px 16px;
  cursor: pointer;
  font-size: 14px;
  color: #606266;
  transition: background 0.15s;
}
.category-item:hover {
  background: #eef1f6;
}
.category-item.active {
  background: #fff;
  color: #409eff;
  font-weight: 600;
  border-right: 3px solid #409eff;
  margin-right: -1px;
}
.cat-icon {
  margin-right: 8px;
  font-size: 16px;
}
.cat-label {
  flex: 1;
}
.cat-count {
  font-size: 12px;
  color: #c0c4cc;
}

.command-pane {
  flex: 1;
  padding: 16px 20px;
  background: #fff;
  display: flex;
  flex-direction: column;
}
.command-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.pane-title {
  font-size: 13px;
  color: #909399;
}
.command-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(150px, 1fr));
  gap: 14px;
  align-content: start;
}
.command-card {
  border: 1px solid #e4e7ed;
  border-radius: 10px;
  padding: 18px 14px;
  cursor: pointer;
  text-align: center;
  transition: all 0.15s;
}
.command-card:hover {
  border-color: #409eff;
  box-shadow: 0 2px 10px rgba(64, 158, 255, 0.15);
  transform: translateY(-2px);
}
.command-card.active {
  border-color: #409eff;
  background: #ecf5ff;
}
.command-icon {
  font-size: 30px;
  margin-bottom: 8px;
}
.command-name {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 4px;
}
.empty-commands {
  text-align: center;
  color: #c0c4cc;
  padding: 60px 0;
  font-size: 14px;
}

/* 参数区（右区底部） */
.param-area {
  margin-top: 20px;
  padding-top: 16px;
  border-top: 1px solid #f0f2f5;
}
.param-header {
  margin-bottom: 12px;
}
.selected-command-hint {
  font-size: 14px;
  font-weight: 500;
  color: #409eff;
}
.command-key {
  color: #909399;
  font-size: 12px;
  font-weight: 400;
}
.no-params {
  color: #909399;
  font-size: 13px;
  padding: 4px 0 8px;
}
.stream-mode-hint {
  font-size: 13px;
  color: #e6a23c;
  background: #fdf6ec;
  border: 1px solid #faecd8;
  border-radius: 4px;
  padding: 8px 12px;
  margin-bottom: 10px;
}
.sim-hint {
  font-size: 12px;
  color: #67c23a;
  background: #f0f9eb;
  border: 1px solid #e1f3d8;
  border-radius: 6px;
  padding: 6px 10px;
  margin-bottom: 12px;
  line-height: 1.5;
  max-width: 520px;
}
.sim-hint-empty {
  color: #909399;
  background: #f4f4f5;
  border-color: #e9e9eb;
}
.stream-current {
  margin-left: 10px;
  font-size: 13px;
  color: #409eff;
}
.param-form {
  max-width: 520px;
}
.param-desc {
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
}
.param-placeholder {
  margin-top: 20px;
  padding: 20px;
  text-align: center;
  color: #c0c4cc;
  font-size: 13px;
  border: 1px dashed #e4e7ed;
  border-radius: 8px;
}

.send-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding-top: 8px;
}
.send-hint {
  font-size: 13px;
  color: #909399;
}

.force-send {
  margin: 0 4px 0 12px;
  white-space: nowrap;
}
.send-hint.warn {
  color: #e6a23c;
}
.music-tag {
  max-width: 240px;
  overflow: hidden;
  text-overflow: ellipsis;
}
.command-card.blocked {
  opacity: 0.45;
}
.command-card.blocked:hover {
  border-color: #e4e7ed;
  box-shadow: none;
  transform: none;
  cursor: not-allowed;
}
</style>

<!-- 命令描述 tooltip：teleport 到 body，scoped 样式不生效，须非 scoped；限宽换行 -->
<style>
.cmd-desc-tip.el-popper {
  max-width: 380px;
  white-space: normal;
  line-height: 1.6;
  word-break: break-all;
}
/* 悬停提示美化（2026-10-28 用户要求美观）：深色渐变底 + 圆角 + 微光边框 + 投影，
   与后台卡片风格协调 */
.cmd-desc-tip.el-popper.is-dark {
  background: linear-gradient(135deg, #1f2d3d 0%, #2b3f55 100%);
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 10px;
  padding: 10px 14px;
  font-size: 13px;
  color: #e8eef5;
  box-shadow:
    0 8px 24px rgba(0, 0, 0, 0.28),
    0 2px 6px rgba(0, 0, 0, 0.18);
}
/* 箭头与气泡同色（覆盖默认纯黑） */
.cmd-desc-tip.el-popper.is-dark .el-popper__arrow::before {
  background: #24374c;
  border: 1px solid rgba(255, 255, 255, 0.14);
}
</style>
