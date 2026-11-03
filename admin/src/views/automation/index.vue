<template>
  <div class="auto-debugger">
    <!-- 顶部工具条 -->
    <div class="toolbar">
      <DevicePicker v-model="deviceId" :devices="devices" only-online placeholder="选择要调试的设备" style="width: 260px" />
      <el-button type="primary" :loading="refreshing" @click="refreshAll">
        <span class="btn-icon">🔄</span>刷新（截图 + 控件树）
      </el-button>
      <el-button :disabled="!deviceId" @click="refreshTree">仅刷新控件树</el-button>
      <el-button :disabled="!deviceId" @click="refreshShot">仅刷新截图</el-button>

      <el-divider direction="vertical" />

      <el-button :disabled="!selectedNode" @click="doClick">点击</el-button>
      <el-button :disabled="!selectedNode" @click="doLongClick">长按</el-button>
      <el-button :disabled="!selectedNode" @click="openInputDialog">输入文本</el-button>

      <el-divider direction="vertical" />

      <el-switch v-model="autoRefresh" active-text="自动刷新" class="auto-refresh" />
      <el-select v-model="refreshInterval" size="small" :disabled="!autoRefresh" class="refresh-interval" @change="restartAutoRefresh">
        <el-option v-for="o in intervalOptions" :key="o.value" :label="o.label" :value="o.value" />
      </el-select>

      <div class="toolbar-right">
        <span v-if="wsState" class="ws-state" :class="wsState">{{ wsStateText }}</span>
      </div>
    </div>

    <!-- 三栏主体 -->
    <div class="body">
      <!-- 左栏：控件树 -->
      <div class="panel tree-panel">
        <div class="panel-head">
          <span class="panel-title">控件树</span>
          <span class="panel-count">{{ nodes.length }} 个节点</span>
        </div>
        <div class="tree-search">
          <el-input v-model="treeFilter" placeholder="过滤 text / desc / id / className" clearable size="small">
            <template #prefix><span>🔍</span></template>
          </el-input>
        </div>
        <div class="tree-scroll">
          <div v-if="!nodes.length" class="empty-hint">
            选择设备后点「刷新」，或从上方工具栏操作
          </div>
          <div v-else v-for="n in filteredNodes" :key="n.key"
               class="tree-node"
               :class="{ selected: n.key === selectedKey, dim: !n.visibleToUser }"
               :style="{ paddingLeft: (n.depth * 14 + 6) + 'px' }"
               @click="selectNode(n)">
            <span v-if="n.hasChildren" class="tree-caret" @click.stop="toggle(n)">
              {{ collapsed.has(n.key) ? '▸' : '▾' }}
            </span>
            <span v-else class="tree-caret leaf">·</span>
            <span class="tree-label" :class="nodeLabelClass(n)">
              {{ nodeLabel(n) }}
            </span>
          </div>
        </div>
      </div>

      <!-- 中栏：截图 + 高亮 -->
      <div class="panel shot-panel">
        <div class="panel-head">
          <span class="panel-title">屏幕截图</span>
          <span class="panel-count">{{ screenW }}×{{ screenH }}</span>
        </div>
        <div ref="shotWrap" class="shot-wrap" @click="onShotClick">
          <div v-if="!shotUrl" class="empty-hint">暂无截图，点「刷新」获取</div>
          <img v-show="false" ref="shotImg" :src="shotUrl" @load="onImgLoad" />
          <canvas ref="shotCanvas" class="shot-canvas"></canvas>
        </div>
        <div class="shot-tips">
          点击截图选中控件 · 悬停高亮 · 拖拽绘制手势坐标
        </div>
      </div>

      <!-- 右栏：属性 + 操作 -->
      <div ref="propPanelRef" class="panel prop-panel">
        <div class="panel-head">
          <span class="panel-title">控件属性</span>
        </div>
        <div v-if="!selectedNode" class="empty-hint">点击左侧控件树或截图上的框选中控件</div>
        <template v-else>
          <div class="prop-scroll" :style="{ flexBasis: propPanelRatio + '%' }">
            <div class="prop-row"><span class="k">text</span><span class="v">{{ selectedNode.text || '—' }}</span></div>
            <div class="prop-row"><span class="k">desc</span><span class="v">{{ selectedNode.desc || '—' }}</span></div>
            <div class="prop-row"><span class="k">id</span><span class="v code">{{ selectedNode.id || '—' }}</span></div>
            <div class="prop-row"><span class="k">className</span><span class="v code">{{ selectedNode.className || '—' }}</span></div>
            <div class="prop-row"><span class="k">package</span><span class="v code">{{ selectedNode.package || '—' }}</span></div>
            <div class="prop-row"><span class="k">bounds</span><span class="v code">{{ boundsText(selectedNode) }}</span></div>
            <div class="prop-flags">
              <el-tag v-for="f in flagList" :key="f.label" size="small" :type="f.on ? 'success' : 'info'" effect="plain">
                {{ f.label }}
              </el-tag>
            </div>
          </div>

          <!-- 可拖动分割线：调整「控件属性」与「选择器」上下比例 -->
          <div class="prop-resizer" @mousedown="startPropResize" title="拖动调整上下比例">
            <span class="resizer-line"></span>
          </div>

          <div class="prop-actions">
            <div class="action-sec">
              选择器（一键复制到脚本）
              <el-checkbox-group v-model="selectorFields" size="small" class="selector-fields">
                <el-checkbox v-for="o in selectorFieldOptions" :key="o.value" :label="o.value" border size="small">{{ o.label }}</el-checkbox>
              </el-checkbox-group>
              <div class="selector-mode-hint">{{ selectorFields.length ? '已勾选 ' + selectorFields.length + ' 个字段（按勾选组合）' : '自动模式（智能单选）' }}</div>
            </div>
            <el-input v-model="selectorText" readonly size="small">
              <template #append>
                <el-button @click="copySelector">复制</el-button>
              </template>
            </el-input>
            <div class="action-sec">参数 JSON</div>
            <el-input v-model="nodeJsonText" readonly size="small" type="textarea" :rows="3">
            </el-input>
            <div class="action-btns">
              <el-button size="small" @click="copyNodeJson">复制 JSON</el-button>
              <el-button size="small" type="primary" plain @click="doClick">点击测试</el-button>
              <el-button size="small" plain @click="doLongClick">长按测试</el-button>
              <el-button size="small" plain @click="openInputDialog">输入测试</el-button>
            </div>
          </div>
        </template>
      </div>
    </div>

    <!-- 输入文本对话框 -->
    <el-dialog v-model="inputVisible" title="输入文本（向选中控件写入）" width="420px" append-to-body>
      <el-input v-model="inputText" type="textarea" :rows="3" placeholder="要写入的文字" />
      <template #footer>
        <el-button @click="inputVisible = false">取消</el-button>
        <el-button type="primary" @click="doSetText">写入</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onBeforeUnmount, watch } from 'vue'
import { ElMessage } from 'element-plus'
import type { Device } from '@/types'
import DevicePicker from '@/components/DevicePicker.vue'
import { listDevices } from '@/api/devices'
import { sendCommand } from '@/api/commands'
import { listMedia, mediaFileUrl } from '@/api/media'
import { getToken } from '@/api/request'
import { wsUrl } from '@/utils/base'

// ============ 节点模型 ============
interface UiNode {
  key: string
  depth: number
  text: string
  desc: string
  id: string
  className: string
  package: string
  bounds: [number, number, number, number] // [l, t, r, b]
  clickable: boolean
  longClickable: boolean
  checkable: boolean
  checked: boolean
  focusable: boolean
  focused: boolean
  selected: boolean
  enabled: boolean
  editable: boolean
  scrollable: boolean
  visibleToUser: boolean
  password: boolean
  hasChildren: boolean
}

// ============ 状态 ============
const devices = ref<Device[]>([])
const deviceId = ref('')
const nodes = ref<UiNode[]>([])
const selectedKey = ref('')
const collapsed = ref<Set<string>>(new Set())
const treeFilter = ref('')

const shotUrl = ref('')
const screenW = ref(0)
const screenH = ref(0)
const shotImg = ref<HTMLImageElement | null>(null)
const shotCanvas = ref<HTMLCanvasElement | null>(null)
const shotWrap = ref<HTMLDivElement | null>(null)

const refreshing = ref(false)
const autoRefresh = ref(false)
const refreshInterval = ref(5)
const intervalOptions = [
  { label: '2 秒', value: 2 },
  { label: '3 秒', value: 3 },
  { label: '5 秒', value: 5 },
  { label: '10 秒', value: 10 },
  { label: '30 秒', value: 30 },
]
const inputVisible = ref(false)
const inputText = ref('')

// 右栏「控件属性 / 选择器」上下比例（属性区占比 %，默认 45%）
const propPanelRatio = ref(45)
const propPanelRef = ref<HTMLDivElement | null>(null)
function startPropResize(e: MouseEvent) {
  const panel = propPanelRef.value
  if (!panel) return
  e.preventDefault()
  const startY = e.clientY
  const startRatio = propPanelRatio.value
  const onMove = (ev: MouseEvent) => {
    const rect = panel.getBoundingClientRect()
    const dy = ev.clientY - startY
    const ratioPx = (dy / rect.height) * 100
    propPanelRatio.value = Math.min(75, Math.max(15, startRatio + ratioPx))
  }
  const onUp = () => {
    document.removeEventListener('mousemove', onMove)
    document.removeEventListener('mouseup', onUp)
  }
  document.addEventListener('mousemove', onMove)
  document.addEventListener('mouseup', onUp)
}

let ws: WebSocket | null = null
const wsState = ref('')
let autoTimer: number | null = null

const wsStateText = computed(() => {
  switch (wsState.value) {
    case 'connecting': return '连接中…'
    case 'connected': return '回执在线'
    case 'failed': return '回执断线'
    default: return ''
  }
})

// ============ 选中节点 ============
const selectedNode = computed<UiNode | null>(
  () => nodes.value.find((n) => n.key === selectedKey.value) ?? null,
)

const flagList = computed(() => {
  const n = selectedNode.value
  if (!n) return []
  return [
    { label: '可点击', on: n.clickable },
    { label: '可长按', on: n.longClickable },
    { label: '可编辑', on: n.editable },
    { label: '可滚动', on: n.scrollable },
    { label: '可勾选', on: n.checkable },
    { label: '已勾选', on: n.checked },
    { label: '已选中', on: n.selected },
    { label: '可用', on: n.enabled },
    { label: '密码框', on: n.password },
  ]
})

const selectorText = computed(() => (selectedNode.value ? buildSelector(selectedNode.value) : ''))
const nodeJsonText = computed(() => {
  const n = selectedNode.value
  if (!n) return ''
  return JSON.stringify({
    text: n.text, desc: n.desc, id: n.id, className: n.className, package: n.package,
    bounds: n.bounds, clickable: n.clickable, editable: n.editable, enabled: n.enabled,
  }, null, 2)
})

// ============ 解析控件树 ============
function parseNodes(msg: string): UiNode[] {
  const start = msg.indexOf('[')
  if (start < 0) return []
  const end = msg.lastIndexOf(']')
  if (end <= start) return []
  try {
    const arr = JSON.parse(msg.slice(start, end + 1))
    if (!Array.isArray(arr)) return []
    return arr.map((n: any, i: number) => {
      const b = Array.isArray(n.bounds) && n.bounds.length === 4
        ? (n.bounds as [number, number, number, number])
        : [0, 0, 0, 0] as [number, number, number, number]
      const cls = n.className ?? ''
      return {
        key: `${i}-${cls}-${b.join(',')}`,
        depth: typeof n.depth === 'number' ? n.depth : -1,
        text: n.text ?? '',
        desc: n.desc ?? '',
        id: n.id ?? '',
        className: cls,
        package: n.package ?? '',
        bounds: b,
        clickable: !!n.clickable,
        longClickable: !!n.longClickable,
        checkable: !!n.checkable,
        checked: !!n.checked,
        focusable: !!n.focusable,
        focused: !!n.focused,
        selected: !!n.selected,
        enabled: n.enabled !== false,
        editable: !!n.editable,
        scrollable: !!n.scrollable,
        visibleToUser: n.visibleToUser !== false,
        password: !!n.password,
        hasChildren: false,
      }
    })
  } catch {
    return []
  }
}

// 标记 hasChildren（DFS 先序：后一个 depth 更大即有子）
function markChildren(list: UiNode[]) {
  for (let i = 0; i < list.length; i++) {
    list[i].hasChildren = i + 1 < list.length && list[i + 1].depth > list[i].depth
  }
}

// ============ 树渲染 ============
const filteredNodes = computed<UiNode[]>(() => {
  const kw = treeFilter.value.trim().toLowerCase()
  const list = nodes.value
  if (!list.length) return []
  // 先按过滤词筛出「可见」集合（含其祖先，保证层级连续）
  let matched = list
  if (kw) {
    const hit = new Set<number>()
    list.forEach((n, i) => {
      if (
        n.text.toLowerCase().includes(kw) ||
        n.desc.toLowerCase().includes(kw) ||
        n.id.toLowerCase().includes(kw) ||
        n.className.toLowerCase().includes(kw)
      ) hit.add(i)
    })
    // 命中节点的祖先也要保留
    const keep = new Set<number>()
    const depthStack: number[] = []
    list.forEach((n, i) => {
      while (depthStack.length && list[depthStack[depthStack.length - 1]].depth >= n.depth) {
        depthStack.pop()
      }
      if (hit.has(i)) {
        depthStack.forEach((p) => keep.add(p))
        keep.add(i)
      }
      depthStack.push(i)
    })
    matched = list.filter((_, i) => keep.has(i))
  }
  // 折叠隐藏
  const out: UiNode[] = []
  let hiddenBelow = -1
  for (let i = 0; i < matched.length; i++) {
    const n = matched[i]
    if (hiddenBelow >= 0 && n.depth <= hiddenBelow) hiddenBelow = -1
    if (hiddenBelow >= 0 && n.depth > hiddenBelow) continue
    out.push(n)
    if (n.hasChildren && collapsed.value.has(n.key)) hiddenBelow = n.depth
  }
  return out
})

function nodeLabel(n: UiNode): string {
  const parts: string[] = []
  if (n.text) parts.push(n.text)
  else if (n.desc) parts.push(n.desc)
  else if (n.id) parts.push(shortId(n.id))
  else parts.push(shortCls(n.className) || '(空)')
  return parts.join(' · ')
}

function nodeLabelClass(n: UiNode): string {
  if (n.password) return 'is-password'
  if (n.editable) return 'is-editable'
  if (n.clickable) return 'is-clickable'
  return ''
}

function shortCls(cls: string): string {
  if (!cls) return ''
  const idx = cls.lastIndexOf('.')
  return idx >= 0 ? cls.slice(idx + 1) : cls
}
function shortId(id: string): string {
  if (!id) return ''
  const idx = id.lastIndexOf('/')
  const base = idx >= 0 ? id.slice(idx + 1) : id
  return base.length > 24 ? base.slice(0, 24) + '…' : base
}

function toggle(n: UiNode) {
  const s = new Set(collapsed.value)
  if (s.has(n.key)) s.delete(n.key)
  else s.add(n.key)
  collapsed.value = s
}

function selectNode(n: UiNode) {
  selectedKey.value = n.key
  drawHighlight()
}

// ============ 选择器生成（可多选组合） ============
// 用户勾选的字段（空数组 = 自动模式，沿用智能单选）
const selectorFields = ref<string[]>([])
const selectorFieldOptions = [
  { label: 'id', value: 'id' },
  { label: 'text', value: 'text' },
  { label: 'desc', value: 'desc' },
  { label: 'className', value: 'className' },
  { label: 'package', value: 'package' },
  { label: 'clickable', value: 'clickable' },
  { label: 'longClickable', value: 'longClickable' },
  { label: 'editable', value: 'editable' },
  { label: 'enabled', value: 'enabled' },
]

function buildSelector(n: UiNode): string {
  const parts: string[] = []
  const fields = selectorFields.value
  // 手动勾选模式：按勾选字段组合
  if (fields.length) {
    if (fields.includes('id') && n.id) parts.push(`id("${n.id}")`)
    if (fields.includes('text') && n.text) parts.push(`text("${n.text}")`)
    if (fields.includes('desc') && n.desc) parts.push(`desc("${n.desc}")`)
    if (fields.includes('className') && n.className) parts.push(`className("${n.className}")`)
    if (fields.includes('package') && n.package) parts.push(`package("${n.package}")`)
    if (fields.includes('clickable') && n.clickable) parts.push('clickable()')
    if (fields.includes('longClickable') && n.longClickable) parts.push('longClickable()')
    if (fields.includes('editable') && n.editable) parts.push('editable()')
    if (fields.includes('enabled') && n.enabled) parts.push('enabled()')
    return parts.join('.') || 'text("")'
  }
  // 自动模式：id > text > desc > className 智能单选（保持原行为）
  if (n.id) parts.push(`id("${n.id}")`)
  else if (n.text) parts.push(`text("${n.text}")`)
  else if (n.desc) parts.push(`desc("${n.desc}")`)
  if (!parts.length && n.className) parts.push(`className("${n.className}")`)
  if (n.clickable) parts.push('clickable()')
  return parts.join('.') || 'text("")'
}

function boundsText(n: UiNode): string {
  return `[${n.bounds[0]},${n.bounds[1]}][${n.bounds[2]},${n.bounds[3]}]`
}

async function copySelector() {
  if (!selectorText.value) return
  try {
    await navigator.clipboard.writeText(selectorText.value)
    ElMessage.success('已复制选择器')
  } catch {
    ElMessage.warning('复制失败')
  }
}
async function copyNodeJson() {
  if (!nodeJsonText.value) return
  try {
    await navigator.clipboard.writeText(nodeJsonText.value)
    ElMessage.success('已复制参数 JSON')
  } catch {
    ElMessage.warning('复制失败')
  }
}

// ============ 截图渲染 + 高亮 ============
function onImgLoad() {
  const img = shotImg.value
  if (!img) return
  screenW.value = img.naturalWidth
  screenH.value = img.naturalHeight
  // 图片加载完成后，尺寸与绘制一起做，避免 watch 异步时序错乱
  fitCanvas()
  drawHighlight()
}

// 按容器宽度等比设置 canvas 尺寸（显示分辨率）
function fitCanvas() {
  const canvas = shotCanvas.value
  const wrap = shotWrap.value
  if (!canvas || !wrap || !screenW.value) return
  const maxW = wrap.clientWidth - 4
  if (maxW <= 0) return
  const ratio = screenH.value / screenW.value
  canvas.width = maxW
  canvas.height = Math.max(1, Math.round(maxW * ratio))
}

// 屏幕坐标 → canvas 显示坐标（等比缩放）
function scaleOf() {
  const canvas = shotCanvas.value
  if (!canvas || !canvas.width || !screenW.value) return 1
  return canvas.width / screenW.value
}

function drawHighlight() {
  const canvas = shotCanvas.value
  const img = shotImg.value
  if (!canvas || !img || !screenW.value || !canvas.width) return
  const ctx = canvas.getContext('2d')
  if (!ctx) return
  // 清空重绘
  ctx.clearRect(0, 0, canvas.width, canvas.height)
  ctx.drawImage(img, 0, 0, canvas.width, canvas.height)

  const s = scaleOf()
  // 先画所有可见节点框（淡色），再画选中节点（高亮）
  for (const n of nodes.value) {
    if (!visibleBounds(n)) continue
    const [l, t, r, b] = n.bounds
    ctx.strokeStyle = n.key === selectedKey.value ? '#ff3b30' : 'rgba(64,158,255,0.55)'
    ctx.lineWidth = n.key === selectedKey.value ? 2.5 : 1
    ctx.strokeRect(l * s, t * s, (r - l) * s, (b - t) * s)
  }
}

function visibleBounds(n: UiNode): boolean {
  const [l, t, r, b] = n.bounds
  return r > l && b > t && l >= 0 && t >= 0
}

function onShotClick(ev: MouseEvent) {
  const canvas = shotCanvas.value
  if (!canvas || !screenW.value) return
  const rect = canvas.getBoundingClientRect()
  const x = (ev.clientX - rect.left) * (screenW.value / rect.width)
  const y = (ev.clientY - rect.top) * (screenH.value / rect.height)
  // 找面积最小、包含该点的节点（最具体）
  let best: UiNode | null = null
  let bestArea = Infinity
  for (const n of nodes.value) {
    const [l, t, r, b] = n.bounds
    if (x >= l && x <= r && y >= t && y <= b) {
      const area = (r - l) * (b - t)
      if (area < bestArea) {
        bestArea = area
        best = n
      }
    }
  }
  if (best) selectNode(best)
}

// ============ 数据获取 ============
async function refreshTree() {
  if (!deviceId.value) return
  const res = await sendCommand(deviceId.value, 'ui_dump', {})
  pendingTreeId = res.command_id
}

async function refreshShot() {
  if (!deviceId.value) return
  const res = await sendCommand(deviceId.value, 'screenshot', {}, false, true)
  pendingShotId = res.command_id
}

async function refreshAll() {
  if (!deviceId.value) {
    ElMessage.warning('请先选择设备')
    return
  }
  refreshing.value = true
  try {
    await refreshShot()
    await refreshTree()
  } finally {
    setTimeout(() => { refreshing.value = false }, 800)
  }
}

let pendingTreeId = ''
let pendingShotId = ''

async function onCommandResult(data: any) {
  if (data.device_id !== deviceId.value) return
  // ui_dump 回执 → 更新控件树
  if (data.command === 'ui_dump') {
    if (data.success) {
      const parsed = parseNodes(data.message || '')
      nodes.value = parsed
      markChildren(nodes.value)
      // 截图高亮要重新画
      drawHighlight()
    } else {
      ElMessage.warning(`ui_dump 失败：${data.message || ''}`)
    }
  }
  // screenshot 回执 → 拉最新截图
  if (data.command === 'screenshot') {
    if (data.success) {
      await loadLatestShot()
    } else {
      ElMessage.warning(`截屏失败：${data.message || ''}`)
    }
  }
  // 其它操作命令回执（点击/长按/输入/滑动）→ 提示 + 自动刷新
  if (['ui_click', 'ui_long_click', 'ui_set_text', 'ui_gesture', 'ui_scroll_to'].includes(data.command)) {
    if (data.success) {
      ElMessage.success('操作成功')
      if (autoRefresh.value) {
        window.setTimeout(() => refreshAll(), 600)
      }
    } else {
      ElMessage.error(`操作失败：${data.message || ''}`)
    }
  }
}

async function loadLatestShot() {
  try {
    const { items } = await listMedia(200)
    const shot = items.find((m) => m.device_id === deviceId.value && m.kind === 'screenshot' && m.exists)
    if (!shot) {
      ElMessage.warning('未找到截图，可能仍在上传')
      return
    }
    // 用带时间戳的 URL 防浏览器缓存，确保拿到最新截图
    shotUrl.value = mediaFileUrl(shot.id) + '&t=' + Date.now()
  } catch (e) {
    ElMessage.error('拉取截图失败')
  }
}

// ============ 操作命令 ============
function doClick() {
  if (!selectedNode.value) return
  sendCommand(deviceId.value, 'ui_click', { selector: buildSelector(selectedNode.value) })
}
function doLongClick() {
  if (!selectedNode.value) return
  // 用 ui_long_click 命令（支持 selector 三级降级，比坐标长按更鲁棒）
  sendCommand(deviceId.value, 'ui_long_click', { selector: buildSelector(selectedNode.value) })
}
function openInputDialog() {
  if (!selectedNode.value) return
  inputText.value = ''
  inputVisible.value = true
}
function doSetText() {
  if (!selectedNode.value) return
  sendCommand(deviceId.value, 'ui_set_text', {
    selector: buildSelector(selectedNode.value), text: inputText.value,
  })
  inputVisible.value = false
}

// ============ WS 回执订阅 ============
function connectWS() {
  const token = getToken()
  if (!token) return
  let url = wsUrl('/api/admin/ws')
  url += `${url.includes('?') ? '&' : '?'}token=${encodeURIComponent(token)}`
  wsState.value = 'connecting'
  ws = new WebSocket(url)
  ws.onopen = () => { wsState.value = 'connected' }
  ws.onclose = () => { wsState.value = 'failed' }
  ws.onerror = () => { wsState.value = 'failed' }
  ws.onmessage = (event) => {
    try {
      const msg = JSON.parse(event.data)
      if (msg.type === 'command_result') {
        onCommandResult(msg.data || {})
      }
    } catch { /* ignore */ }
  }
}

// ============ 自动刷新 ============
function restartAutoRefresh() {
  if (autoTimer) { window.clearInterval(autoTimer); autoTimer = null }
  if (autoRefresh.value && deviceId.value) {
    autoTimer = window.setInterval(() => refreshAll(), refreshInterval.value * 1000)
  }
}
watch(autoRefresh, () => restartAutoRefresh())

watch(deviceId, () => {
  nodes.value = []
  shotUrl.value = ''
  screenW.value = 0
  screenH.value = 0
  selectedKey.value = ''
})

onMounted(() => {
  connectWS()
  loadDevices()
})

async function loadDevices() {
  try {
    devices.value = await listDevices()
  } catch { /* ignore */ }
}

onBeforeUnmount(() => {
  if (ws) ws.close()
  if (autoTimer) window.clearInterval(autoTimer)
})
</script>

<style scoped>
.auto-debugger {
  height: 100%;
  display: flex;
  flex-direction: column;
  padding: 12px;
  box-sizing: border-box;
  gap: 12px;
}
.toolbar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}
.btn-icon { margin-right: 2px; }
.auto-refresh { margin-left: 4px; }
.refresh-interval { width: 88px; margin-left: 4px; }
.toolbar-right { margin-left: auto; }
.ws-state { font-size: 12px; padding: 2px 8px; border-radius: 10px; }
.ws-state.connected { color: #2e9e5b; background: #e8f7ef; }
.ws-state.connecting { color: #b8860b; background: #fdf6e3; }
.ws-state.failed { color: #e5484d; background: #fdeaea; }

.body {
  flex: 1;
  display: grid;
  grid-template-columns: 300px 1fr 320px;
  gap: 12px;
  min-height: 0;
}
.panel {
  background: #fff;
  border: 1px solid #e5e7eb;
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
.panel-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 12px;
  border-bottom: 1px solid #f0f0f0;
}
.panel-title { font-weight: 600; font-size: 14px; }
.panel-count { font-size: 12px; color: #999; }

.empty-hint {
  padding: 24px 16px;
  color: #999;
  font-size: 13px;
  text-align: center;
}

/* 控件树 */
.tree-search { padding: 8px; border-bottom: 1px solid #f0f0f0; }
.tree-scroll { flex: 1; overflow: auto; }
.tree-node {
  display: flex;
  align-items: center;
  cursor: pointer;
  font-size: 13px;
  line-height: 1.5;
  padding: 3px 8px;
  white-space: nowrap;
  overflow: hidden;
}
.tree-node:hover { background: #f5f7fa; }
.tree-node.selected { background: #e6f1ff; }
.tree-node.dim { opacity: 0.45; }
.tree-caret { width: 14px; flex-shrink: 0; color: #999; font-size: 12px; }
.tree-caret.leaf { font-size: 10px; }
.tree-label { overflow: hidden; text-overflow: ellipsis; }
.tree-label.is-clickable { color: #2f6fd6; font-weight: 500; }
.tree-label.is-editable { color: #b8860b; }
.tree-label.is-password { color: #e5484d; }

/* 截图 */
.shot-panel { display: flex; flex-direction: column; }
.shot-wrap {
  flex: 1;
  position: relative;
  overflow: auto;
  display: flex;
  align-items: flex-start;
  justify-content: center;
  background: #1a1a1a;
}
.shot-canvas { display: block; max-width: 100%; cursor: crosshair; }
.shot-tips {
  padding: 8px 12px;
  font-size: 12px;
  color: #999;
  border-top: 1px solid #f0f0f0;
  text-align: center;
}

/* 属性面板 */
.prop-panel { display: flex; flex-direction: column; }
.prop-scroll { flex: 0 1 auto; overflow: auto; padding: 8px 12px; min-height: 60px; }
.prop-resizer {
  flex-shrink: 0;
  height: 8px;
  cursor: row-resize;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #fafafa;
  border-top: 1px solid #f0f0f0;
  border-bottom: 1px solid #f0f0f0;
}
.prop-resizer:hover { background: #eef3fb; }
.prop-resizer .resizer-line { width: 40px; height: 3px; border-radius: 2px; background: #c8cbd4; }
.prop-row {
  display: flex;
  gap: 8px;
  padding: 5px 0;
  border-bottom: 1px dashed #f0f0f0;
  font-size: 13px;
}
.prop-row .k { width: 76px; color: #999; flex-shrink: 0; }
.prop-row .v { flex: 1; word-break: break-all; }
.prop-row .v.code { font-family: monospace; font-size: 12px; }
.prop-flags { display: flex; flex-wrap: wrap; gap: 4px; padding: 8px 0; }
.prop-actions {
  flex: 1 1 auto;
  min-height: 0;
  overflow: auto;
  padding: 10px 12px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.selector-fields { display: flex; flex-wrap: wrap; gap: 4px; margin-top: 6px; }
.selector-fields .el-checkbox { margin-right: 0; }
.selector-mode-hint { font-size: 11px; color: #b0b0b0; margin-top: 4px; }
.action-sec { font-size: 12px; color: #999; margin-top: 4px; }
.action-btns { display: flex; flex-wrap: wrap; gap: 6px; }
</style>
