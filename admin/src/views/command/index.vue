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
            <div
              v-for="cmd in filteredCommands"
              :key="cmd.name"
              class="command-card"
              :class="{ active: selectedCommand?.name === cmd.name, blocked: isBlocked(cmd) }"
              @click="selectCommand(cmd)"
            >
              <div class="command-icon">{{ cmd.icon }}</div>
              <div class="command-name">{{ cmd.label }}</div>
              <div class="command-desc">{{ cmd.description }}</div>
            </div>
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
              <el-form-item
                v-for="p in selectedCommand.params"
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
                    v-for="o in p.options || []"
                    :key="o.value"
                    :label="o.label"
                    :value="o.value"
                  />
                </el-select>
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
              <el-tooltip
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
        <el-table-column label="状态" width="100">
          <template #default="{ row }">
            <el-tag :type="statusType(row.status)" size="small">
              {{ statusText(row.status) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="回执信息" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.result_message || '-' }}</template>
        </el-table-column>
        <el-table-column label="创建时间" width="180">
          <template #default="{ row }">{{ formatTime(row.created_at) }}</template>
        </el-table-column>
      </el-table>
    </el-card>
  </div>
</template>

<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { Refresh, Search } from '@element-plus/icons-vue'
import { getMusicState, listDevices } from '@/api/devices'
import type { MusicState } from '@/api/devices'
import { listCommands, sendCommand, getCommandCatalog } from '@/api/commands'
import { getToken } from '@/api/request'
import { wsUrl } from '@/utils/base'
import { formatTime } from '@/utils/format'
import DevicePicker from '@/components/DevicePicker.vue'
import LocationResultCard from '@/components/LocationResultCard.vue'
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

const form = reactive({
  deviceId: (route.query.deviceId as string) || '',
})

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
// 音乐播放状态：目标设备的实时状态徽标 + 控制类命令按钮联动
// ---------------------------------------------------------------------------

const musicState = ref<MusicState | null>(null)

// 定位结果卡片：下发 get_location 后记录 command_id，回执推送按 ID 匹配触发地图展示
const locationCard = ref<any>(null)
let pendingLocationCommandId = ''
let locationSentAt = 0

/** 控制类命令允许下发的设备状态（播放音乐不受限，设备在线即可）。 */
const MUSIC_STATE_RULES: Record<string, string[]> = {
  pause_music: ['playing'],
  resume_music: ['paused'],
  stop_music: ['playing', 'paused'],
}

/** 强制下发：开启后绕过状态联动禁用，交由客户端按真实状态校验（状态不匹配回执失败）。 */
const forceSend = ref(false)

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

const commandBlocked = computed(() =>
  selectedCommand.value ? isBlocked(selectedCommand.value) : false,
)

const blockHint = computed(() => {
  if (!selectedCommand.value) return ''
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
    loadMusicState()
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

function selectCommand(cmd: CommandDef) {
  selectedCommand.value = cmd
  // 重置参数：按 default 或类型给初值
  for (const p of cmd.params) {
    if (p.default !== undefined && p.default !== null) {
      params[p.name] = p.default
    } else {
      params[p.name] = p.type === 'switch' ? false : ''
    }
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
  sending.value = true
  try {
    const r = await sendCommand(
      form.deviceId,
      selectedCommand.value.name,
      collectParams(),
    )
    ElMessage.success(`命令已下发（${r.command_id.slice(0, 8)}…）`)
    // get_location：记下命令 ID 与下发时刻，等回执推送匹配后展示定位结果地图
    if (selectedCommand.value.name === 'get_location') {
      pendingLocationCommandId = r.command_id
      locationSentAt = Date.now()
    }
    load()
  } catch {
    // 错误已统一提示
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
  }
  ws.onmessage = (event) => {
    try {
      const msg = JSON.parse(event.data)
      if (msg.type === 'command_result') {
        load()
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
      } else if (msg.type === 'music_state') {
        // 实时音乐状态推送：仅更新当前目标设备
        if (msg.data.device_id === form.deviceId) {
          musicState.value = msg.data
        }
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
  reconnectTimer = window.setTimeout(() => {
    reconnectTimer = null
    connectWS()
  }, 3000)
}

onMounted(() => {
  loadDevices()
  load()
  loadCatalog()
  connectWS()
})

onBeforeUnmount(() => {
  if (reconnectTimer) window.clearTimeout(reconnectTimer)
  reconnectTimer = null
  ws?.close()
})
</script>

<style scoped>
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
.command-desc {
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
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
