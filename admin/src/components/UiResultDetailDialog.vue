<template>
  <el-dialog v-model="visible" :title="title" width="860px" destroy-on-close class="ui-detail">
    <template v-if="record">
      <!-- 通用：命令元信息条 -->
      <div class="ui-meta">
        <el-tag :type="statusTagType" size="small" effect="dark">{{ statusText }}</el-tag>
        <span>ID {{ record.command_id.slice(0, 8) }}…</span>
        <span>设备 {{ deviceLabel }}</span>
        <span>下发 {{ formatTime(record.created_at) }}</span>
        <span v-if="record.result_at">回执 {{ formatTime(record.result_at) }}</span>
      </div>

      <!-- ① ui_dump：控件树可视化（按 depth 缩进 + 可折叠） -->
      <template v-if="kind === 'ui_dump'">
        <div v-if="!parseOk" class="ui-empty">
          {{ parseError }}
          <div class="ui-tip-inline">可能是旧版 APK（节点未带 depth 字段），请重新打包后再下发 ui_dump。</div>
        </div>
        <template v-else>
          <div class="ui-summary">
            <el-tag type="info" size="small">共 {{ nodes.length }} 个节点</el-tag>
            <span class="ui-summary-note">根节点为当前窗口顶层，逐级缩进展示；点箭头展开/收起子节点</span>
          </div>
          <div class="ui-tree-wrap">
            <div
              v-for="n in treeNodes"
              :key="n.key"
              :style="{ paddingLeft: n.depth * 18 + 'px' }"
              class="ui-tree-row"
              :class="{ 'ui-tree-row-active': selectedKey === n.key }"
              @click="selectNode(n)"
            >
              <span
                class="ui-tree-toggle"
                :class="{ 'ui-tree-toggle-leaf': !n.hasChildren }"
                @click.stop="n.hasChildren && toggle(n)"
              >
                <span v-if="n.hasChildren" class="ui-tree-arrow" :class="{ open: n.open }">▸</span>
                <span v-else class="ui-tree-dot">·</span>
              </span>
              <span class="ui-node-label" :class="{ 'ui-node-clickable': n.clickable }">
                <span class="ui-node-cls" :title="n.className">{{ shortCls(n.className) }}</span>
                <span v-if="n.label" class="ui-node-text" :title="n.label">{{ n.label }}</span>
                <span v-else class="ui-node-text ui-node-text-empty">（无文本）</span>
                <span v-if="n.id" class="ui-node-id" :title="n.id">#{{ shortId(n.id) }}</span>
              </span>
              <span class="ui-node-flags">
                <el-tag v-if="n.clickable" type="success" size="small" effect="plain">可点击</el-tag>
                <el-tag v-if="n.editable" type="warning" size="small" effect="plain">可编辑</el-tag>
                <el-tag v-if="!n.enabled" type="info" size="small" effect="plain">禁用</el-tag>
                <span v-if="n.bounds" class="ui-node-bounds" :title="n.bounds">{{ n.bounds }}</span>
              </span>
            </div>
            <div v-if="!treeNodes.length" class="ui-empty">控件树为空（当前窗口可能无可读节点）。</div>
          </div>
        </template>
      </template>

      <!-- ② ui_find：匹配节点列表卡片 -->
      <template v-else-if="kind === 'ui_find'">
        <div v-if="!parseOk" class="ui-empty">{{ parseError }}</div>
        <template v-else>
          <div class="ui-summary">
            <el-tag type="info" size="small">{{ findCountText }}</el-tag>
            <span v-if="record.params?.selector" class="ui-summary-note">
              选择器：<code>{{ record.params.selector }}</code>
            </span>
          </div>
          <div v-if="nodes.length" class="ui-find-list">
            <div v-for="(n, i) in nodes" :key="i" class="ui-find-card">
              <div class="ui-find-head">
                <span class="ui-find-index">#{{ i }}</span>
                <span class="ui-node-cls">{{ shortCls(n.className) }}</span>
                <span class="ui-node-flags">
                  <el-tag v-if="n.clickable" type="success" size="small" effect="plain">可点击</el-tag>
                  <el-tag v-if="n.editable" type="warning" size="small" effect="plain">可编辑</el-tag>
                </span>
              </div>
              <div v-if="n.label" class="ui-find-text">{{ n.label }}</div>
              <div class="ui-kv">
                <div class="ui-kv-item"><span class="ui-kv-label">id</span><span class="ui-kv-value">{{ n.id || '—' }}</span></div>
                <div class="ui-kv-item"><span class="ui-kv-label">package</span><span class="ui-kv-value">{{ n.package || '—' }}</span></div>
                <div class="ui-kv-item"><span class="ui-kv-label">bounds</span><span class="ui-kv-value">{{ n.bounds || '—' }}</span></div>
                <div class="ui-kv-item"><span class="ui-kv-label">className</span><span class="ui-kv-value">{{ n.className || '—' }}</span></div>
              </div>
            </div>
          </div>
          <div v-else class="ui-empty">未匹配到任何控件。</div>
        </template>
      </template>

      <!-- 通用：回执原文 -->
      <div class="ui-raw">
        <div class="ui-raw-head">
          <span>回执原文</span>
          <el-button size="small" link type="primary" @click="copyRaw">复制</el-button>
        </div>
        <pre class="ui-raw-body">{{ rawText }}</pre>
      </div>
    </template>
  </el-dialog>

  <!-- 控件详情抽屉：点击树节点后展示该节点的全部属性 -->
  <el-drawer v-model="detailVisible" :title="detailTitle" size="460px" append-to-body class="ui-node-drawer">
    <template v-if="selected">
      <div class="ui-drawer-hero">
        <span class="ui-node-cls">{{ shortCls(selected.className) }}</span>
        <span v-if="selected.label" class="ui-drawer-text">{{ selected.label }}</span>
        <span class="ui-drawer-hero-actions">
          <el-button size="small" type="primary" plain @click="copySelector">复制选择器</el-button>
          <el-button size="small" plain @click="copyNodeJson">复制参数 JSON</el-button>
        </span>
      </div>
      <div class="ui-drawer-hint">点树中其它节点可切换；此面板汇总该控件在无障碍接口下的全部字段。可一键复制选择器表达式或参数 JSON，直接粘进 Python 脚本或 ui_find/ui_click 命令。</div>

      <div class="ui-drawer-section">
        <div class="ui-drawer-sec-title">标识</div>
        <div class="ui-drawer-kv">
          <div class="ui-drawer-kv-item"><span class="k">text</span><span class="v">{{ selected.text || '—' }}</span></div>
          <div class="ui-drawer-kv-item"><span class="k">desc</span><span class="v">{{ selected.desc || '—' }}</span></div>
          <div class="ui-drawer-kv-item"><span class="k">id</span><span class="v">{{ selected.id || '—' }}</span></div>
          <div class="ui-drawer-kv-item"><span class="k">className</span><span class="v">{{ selected.className || '—' }}</span></div>
          <div class="ui-drawer-kv-item"><span class="k">package</span><span class="v">{{ selected.package || '—' }}</span></div>
          <div class="ui-drawer-kv-item"><span class="k">windowId</span><span class="v">{{ selected.windowId }}</span></div>
        </div>
      </div>

      <div class="ui-drawer-section">
        <div class="ui-drawer-sec-title">坐标</div>
        <div class="ui-drawer-kv">
          <div class="ui-drawer-kv-item"><span class="k">bounds(屏幕)</span><span class="v">{{ selected.bounds || '—' }}</span></div>
          <div class="ui-drawer-kv-item"><span class="k">bounds(父容器)</span><span class="v">{{ selected.boundsInParent || '—' }}</span></div>
        </div>
      </div>

      <div class="ui-drawer-section">
        <div class="ui-drawer-sec-title">状态（布尔）</div>
        <div class="ui-drawer-flags">
          <el-tag v-for="f in boolFields" :key="f.key" :type="f.val ? 'success' : 'info'" size="small" :effect="f.val ? 'light' : 'plain'">
            {{ f.label }}{{ f.val ? ' ✓' : '' }}
          </el-tag>
        </div>
      </div>

      <div class="ui-drawer-section">
        <div class="ui-drawer-sec-title">层级 / 集合 <span class="ui-drawer-sec-tip">鼠标悬停字段名可查看含义</span></div>
        <div class="ui-drawer-kv">
          <div class="ui-drawer-kv-item">
            <el-tooltip content="节点在控件树中的层级深度，0=根节点，越大越靠叶子" placement="top"><span class="k ui-k-hint">depth</span></el-tooltip>
            <span class="v">{{ selected.depth }}</span>
          </div>
          <div class="ui-drawer-kv-item">
            <el-tooltip content="直接子节点的数量" placement="top"><span class="k ui-k-hint">childCount</span></el-tooltip>
            <span class="v">{{ selected.childCount }}</span>
          </div>
          <div class="ui-drawer-kv-item">
            <el-tooltip content="绘制顺序，值越大越后绘制（越靠上层显示）" placement="top"><span class="k ui-k-hint">drawingOrder</span></el-tooltip>
            <span class="v">{{ selected.drawingOrder }}</span>
          </div>
          <div class="ui-drawer-kv-item">
            <el-tooltip content="该控件在列表/网格中的行号和列号（0 开始），普通控件通常为 0/0" placement="top"><span class="k ui-k-hint">row/column</span></el-tooltip>
            <span class="v">{{ selected.row }}/{{ selected.column }}</span>
          </div>
          <div class="ui-drawer-kv-item">
            <el-tooltip content="该控件占几行几列（合并单元格），普通控件通常为 1/1" placement="top"><span class="k ui-k-hint">rowSpan/colSpan</span></el-tooltip>
            <span class="v">{{ selected.rowSpan }}/{{ selected.columnSpan }}</span>
          </div>
          <div class="ui-drawer-kv-item">
            <el-tooltip content="所属列表/网格的总行数和总列数，仅在列表、网格类控件上有值" placement="top"><span class="k ui-k-hint">rowCount/colCount</span></el-tooltip>
            <span class="v">{{ selected.rowCount }}/{{ selected.columnCount }}</span>
          </div>
        </div>
      </div>

      <div class="ui-drawer-section">
        <div class="ui-drawer-sec-title">输入相关 <span class="ui-drawer-sec-tip">鼠标悬停字段名可查看含义</span></div>
        <div class="ui-drawer-kv">
          <div class="ui-drawer-kv-item">
            <el-tooltip content="允许输入的最大字符数，-1 表示不限" placement="top"><span class="k ui-k-hint">maxTextLength</span></el-tooltip>
            <span class="v">{{ selected.maxTextLength }}</span>
          </div>
          <div class="ui-drawer-kv-item">
            <el-tooltip content="当前文本光标选中的起止位置（字符下标），-1 表示无选中" placement="top"><span class="k ui-k-hint">textSelection</span></el-tooltip>
            <span class="v">{{ selected.textSelectionStart }}~{{ selected.textSelectionEnd }}</span>
          </div>
          <div class="ui-drawer-kv-item">
            <el-tooltip content="输入框的类型（数字/文本/密码等），对应系统定义的 inputType 掩码值" placement="top"><span class="k ui-k-hint">inputType</span></el-tooltip>
            <span class="v">{{ selected.inputType }}</span>
          </div>
          <div class="ui-drawer-kv-item">
            <el-tooltip content="输入框为空时显示的灰色占位提示文字" placement="top"><span class="k ui-k-hint">hintText</span></el-tooltip>
            <span class="v">{{ selected.hintText || '—' }}</span>
          </div>
          <div class="ui-drawer-kv-item">
            <el-tooltip content="输入框的校验错误提示文字（如「密码格式不正确」）" placement="top"><span class="k ui-k-hint">error</span></el-tooltip>
            <span class="v">{{ selected.error || '—' }}</span>
          </div>
          <div class="ui-drawer-kv-item">
            <el-tooltip content="控件的悬浮提示气泡文字（长按/悬停时弹出）" placement="top"><span class="k ui-k-hint">tooltipText</span></el-tooltip>
            <span class="v">{{ selected.tooltipText || '—' }}</span>
          </div>
          <div class="ui-drawer-kv-item">
            <el-tooltip content="控件所属面板/区域的标题（如侧边栏、面板的名称）" placement="top"><span class="k ui-k-hint">paneTitle</span></el-tooltip>
            <span class="v">{{ selected.paneTitle || '—' }}</span>
          </div>
          <div class="ui-drawer-kv-item">
            <el-tooltip content="动态区域类型：0=无，1=礼貌播报，2=主动打断播报（供读屏软件用）" placement="top"><span class="k ui-k-hint">liveRegion</span></el-tooltip>
            <span class="v">{{ selected.liveRegion }}</span>
          </div>
          <div class="ui-drawer-kv-item">
            <el-tooltip content="支持的文字移动粒度（按字符/词/行/段移动光标），供读屏软件用" placement="top"><span class="k ui-k-hint">movementGranularities</span></el-tooltip>
            <span class="v">{{ selected.movementGranularities }}</span>
          </div>
        </div>
      </div>

      <div class="ui-drawer-section">
        <div class="ui-drawer-sec-title">可执行动作（{{ selected.actions.length }}）</div>
        <div v-if="selected.actions.length" class="ui-drawer-actions">
          <div v-for="a in selected.actions" :key="a.id" class="ui-drawer-action">
            <span class="ui-drawer-action-id">{{ a.id }}</span>
            <span class="ui-drawer-action-label">{{ actionLabel(a) }}</span>
          </div>
        </div>
        <div v-else class="ui-empty">无动作</div>
      </div>
    </template>
  </el-drawer>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { CommandRecord } from '@/types'
import { formatTime } from '@/utils/format'

/**
 * ui_dump / ui_find 两条命令的「查看详情」弹窗（命令下发页 · 命令历史回执列）。
 *
 * 设计要点（用户 2026-11-03 拍板，遵循「按命令单独设计」约定）：
 *   ① ui_dump → 控件树可视化：回执是 DFS 先序的扁平 JSON 数组（每个节点带 depth），
 *      后台按 depth 重建缩进树 + 折叠态；节点显示 className/text/desc/id/bounds 与
 *      clickable/editable 徽标。depth 字段需 APK v0.2.39+（nodeToJson 新增 depth）。
 *   ② ui_find → 匹配节点列表卡片：回执「匹配 N 个控件：[...]」，逐个卡片展示 KV。
 * 数据来源仅限现有 CommandRecord（params + result_message），不回显额外敏感信息。
 */

// 单个节点（从回执 JSON 解析出的字段，全部可选以容错）
interface UiNode {
  key: string
  depth: number
  text: string
  desc: string
  id: string
  className: string
  package: string
  bounds: string
  boundsInParent: string
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
  accessibilityFocused: boolean
  password: boolean
  contentInvalid: boolean
  screenReaderFocusable: boolean
  dismissable: boolean
  multiLine: boolean
  importantForAccessibility: boolean
  childCount: number
  drawingOrder: number
  windowId: number
  row: number
  column: number
  rowSpan: number
  columnSpan: number
  rowCount: number
  columnCount: number
  maxTextLength: number
  textSelectionStart: number
  textSelectionEnd: number
  inputType: number
  error: string
  hintText: string
  tooltipText: string
  paneTitle: string
  liveRegion: number
  movementGranularities: number
  actions: { id: number; label: string }[]
  // 树视图扩展字段
  label: string
  hasChildren: boolean
  open: boolean
}

const props = defineProps<{ deviceNames?: Record<string, string> }>()

const visible = ref(false)
const record = ref<CommandRecord | null>(null)

function show(row: CommandRecord) {
  record.value = row
  visible.value = true
  // 每次打开重置折叠态（默认全展开第一层）
  initTree()
}
defineExpose({ show })

type TagType = 'success' | 'warning' | 'danger' | 'info'

const kind = computed(() => record.value?.command ?? '')
const rawText = computed(() => record.value?.result_message || '（暂无回执）')
const deviceLabel = computed(() => {
  const id = record.value?.device_id ?? ''
  return props.deviceNames?.[id] || id
})
const title = computed(() => {
  switch (kind.value) {
    case 'ui_dump': return '界面结构 · 查看详情'
    case 'ui_find': return '查找控件 · 匹配结果'
    default: return '命令详情'
  }
})
const statusText = computed(() => {
  switch (record.value?.status) {
    case 'success': return '执行成功'
    case 'failed': return '执行失败'
    default: return '待回执'
  }
})
const statusTagType = computed<TagType>(() => {
  switch (record.value?.status) {
    case 'success': return 'success'
    case 'failed': return 'danger'
    default: return 'info'
  }
})

// ---- 回执解析：提取「[...]」JSON 数组，兼容 ui_find 的「匹配 N 个控件：」前缀 ----
const parseOk = ref(true)
const parseError = ref('')
const nodes = ref<UiNode[]>([])

function boundsOf(b: any): string {
  if (Array.isArray(b) && b.length === 4) return `[${b[0]},${b[1]}][${b[2]},${b[3]}]`
  return ''
}

function parseNodes(msg: string): UiNode[] {
  // ui_dump 回执整个就是 JSON 数组；ui_find 回执是「匹配 N 个控件：<json数组>」
  const start = msg.indexOf('[')
  if (start < 0) {
    parseOk.value = false
    parseError.value = '回执中未找到控件 JSON 数据（可能是执行失败或格式异常）。'
    return []
  }
  // 取最后一个 ']' 作为数组结束（bounds 里也有逗号但无嵌套 '['），从第一个 '[' 到最后一个 ']'
  const end = msg.lastIndexOf(']')
  if (end <= start) {
    parseOk.value = false
    parseError.value = '回执中的控件数据不完整。'
    return []
  }
  const jsonStr = msg.slice(start, end + 1)
  try {
    const arr = JSON.parse(jsonStr)
    if (!Array.isArray(arr)) throw new Error('not array')
    parseOk.value = true
    return arr.map((n: any, i: number) => {
      const text = n.text ?? ''
      const desc = n.desc ?? ''
      const id = n.id ?? ''
      const cls = n.className ?? ''
      const bounds = boundsOf(n.bounds)
      return {
        key: `${i}-${cls}-${bounds}`,
        depth: typeof n.depth === 'number' ? n.depth : -1,
        text,
        desc,
        id,
        className: cls,
        package: n.package ?? '',
        bounds,
        boundsInParent: boundsOf(n.boundsInParent),
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
        visibleToUser: !!n.visibleToUser,
        accessibilityFocused: !!n.accessibilityFocused,
        password: !!n.password,
        contentInvalid: !!n.contentInvalid,
        screenReaderFocusable: !!n.screenReaderFocusable,
        dismissable: !!n.dismissable,
        multiLine: !!n.multiLine,
        importantForAccessibility: !!n.importantForAccessibility,
        childCount: typeof n.childCount === 'number' ? n.childCount : 0,
        drawingOrder: typeof n.drawingOrder === 'number' ? n.drawingOrder : 0,
        windowId: typeof n.windowId === 'number' ? n.windowId : 0,
        row: typeof n.row === 'number' ? n.row : -1,
        column: typeof n.column === 'number' ? n.column : -1,
        rowSpan: typeof n.rowSpan === 'number' ? n.rowSpan : -1,
        columnSpan: typeof n.columnSpan === 'number' ? n.columnSpan : -1,
        rowCount: typeof n.rowCount === 'number' ? n.rowCount : 0,
        columnCount: typeof n.columnCount === 'number' ? n.columnCount : 0,
        maxTextLength: typeof n.maxTextLength === 'number' ? n.maxTextLength : 0,
        textSelectionStart: typeof n.textSelectionStart === 'number' ? n.textSelectionStart : -1,
        textSelectionEnd: typeof n.textSelectionEnd === 'number' ? n.textSelectionEnd : -1,
        inputType: typeof n.inputType === 'number' ? n.inputType : 0,
        error: n.error ?? '',
        hintText: n.hintText ?? '',
        tooltipText: n.tooltipText ?? '',
        paneTitle: n.paneTitle ?? '',
        liveRegion: typeof n.liveRegion === 'number' ? n.liveRegion : 0,
        movementGranularities: typeof n.movementGranularities === 'number' ? n.movementGranularities : 0,
        actions: Array.isArray(n.actions) ? n.actions : [],
        label: text || desc,
        hasChildren: false,
        open: true,
      }
    })
  } catch (e) {
    parseOk.value = false
    parseError.value = `控件 JSON 解析失败：${(e as Error).message}`
    return []
  }
}

function initTree() {
  const msg = rawText.value
  nodes.value = parseNodes(msg)
}

// ---- 树视图：按 depth 重建父子可见性 ----
const collapsedKeys = ref<Set<string>>(new Set())

function toggle(n: UiNode) {
  if (collapsedKeys.value.has(n.key)) collapsedKeys.value.delete(n.key)
  else collapsedKeys.value.add(n.key)
  // 触发响应式更新
  collapsedKeys.value = new Set(collapsedKeys.value)
}

// ---- 选中节点 → 详情抽屉 ----
const selectedKey = ref('')
const detailVisible = ref(false)
const selected = computed<UiNode | null>(
  () => nodes.value.find((n) => n.key === selectedKey.value) ?? null,
)
const detailTitle = computed(() => {
  if (!selected.value) return '控件详情'
  const t = selected.value.label || selected.value.className || '控件'
  return `控件详情 · ${shortCls(selected.value.className)}`
})

function selectNode(n: UiNode) {
  selectedKey.value = n.key
  detailVisible.value = true
}

// 布尔状态字段：详情面板以标签形式展示
const boolFields = computed(() => {
  const n = selected.value
  if (!n) return []
  const fields: { key: string; label: string; val: boolean }[] = [
    { key: 'clickable', label: '可点击', val: n.clickable },
    { key: 'longClickable', label: '可长按', val: n.longClickable },
    { key: 'checkable', label: '可勾选', val: n.checkable },
    { key: 'checked', label: '已勾选', val: n.checked },
    { key: 'focusable', label: '可聚焦', val: n.focusable },
    { key: 'focused', label: '已聚焦', val: n.focused },
    { key: 'selected', label: '已选中', val: n.selected },
    { key: 'enabled', label: '可用', val: n.enabled },
    { key: 'editable', label: '可编辑', val: n.editable },
    { key: 'scrollable', label: '可滚动', val: n.scrollable },
    { key: 'visibleToUser', label: '对用户可见', val: n.visibleToUser },
    { key: 'accessibilityFocused', label: '无障碍焦点', val: n.accessibilityFocused },
    { key: 'password', label: '密码输入', val: n.password },
    { key: 'contentInvalid', label: '内容非法', val: n.contentInvalid },
    { key: 'screenReaderFocusable', label: '读屏可聚焦', val: n.screenReaderFocusable },
    { key: 'dismissable', label: '可关闭', val: n.dismissable },
    { key: 'multiLine', label: '多行', val: n.multiLine },
    { key: 'importantForAccessibility', label: '对无障碍重要', val: n.importantForAccessibility },
  ]
  return fields
})

// 动作 id → 可读名称
function actionLabel(a: { id: number; label: string }): string {
  if (a.label) return a.label
  const names: Record<number, string> = {
    1: '聚焦', 2: '清除焦点', 4: '选择', 8: '清除选择', 16: '点击', 32: '长按',
    64: '无障碍焦点', 128: '清除无障碍焦点', 256: '滚动前进', 512: '滚动后退',
    1024: '向上滚动', 4096: '向下滚动', 8192: '向左滚动', 16384: '向右滚动',
    65536: '展开', 131072: '折叠', 262144: '关闭', 524288: '下一页面',
    1048576: '上一页面', 2097152: '显示在屏幕上', 4194304: '滚动到指定位置',
    16908344: '文本选择开始', 16908345: '文本选择结束', 16908354: '剪贴板',
    16908372: '设置选中', 16908373: '设置进度', 16908374: '设置文本', 16908375: '显示上下文菜单',
  }
  return names[a.id] ?? `动作 ${a.id}`
}

// 计算「可见节点」：若某祖先被折叠，其后代（depth 更大）隐藏
const treeNodes = computed<UiNode[]>(() => {
  const list = nodes.value
  if (!list.length) return []
  // 判断父子关系：DFS 先序中，节点 i 是节点 j 的祖先 ⇔ depth[i] < depth[j] 且 i 是最后一个满足 depth < depth[j] 的前驱
  // 用栈记录当前可见祖先链；被折叠的节点不 push 后代
  const out: UiNode[] = []
  // 标记每个节点是否有子节点（存在后续 depth 更大的节点，且中间没有回到它的 depth）
  for (let i = 0; i < list.length; i++) {
    list[i].hasChildren = i + 1 < list.length && list[i + 1].depth > list[i].depth
  }
  // 可见性：维护折叠深度阈值
  // 简化：栈存储「最近一个被折叠节点的 depth」，若当前节点 depth > 折叠depth 则隐藏
  let hiddenBelow = -1
  for (let i = 0; i < list.length; i++) {
    const n = list[i]
    // 若上一个折叠节点的 depth 更浅，且当前节点回到该深度以下，则解除隐藏
    // 用显式栈模拟折叠链
    if (hiddenBelow >= 0 && n.depth <= hiddenBelow) hiddenBelow = -1
    if (hiddenBelow >= 0 && n.depth > hiddenBelow) {
      // 该节点被祖先折叠隐藏
      continue
    }
    out.push(n)
    if (n.hasChildren && collapsedKeys.value.has(n.key)) {
      hiddenBelow = n.depth
    }
  }
  return out
})

// ---- ui_find 命中数 ----
const findCountText = computed(() => {
  const m = rawText.value.match(/匹配\s+(\d+)\s+个控件/)
  return m ? `匹配 ${m[1]} 个控件` : `匹配 ${nodes.value.length} 个控件`
})

// ---- 展示辅助 ----
function shortCls(cls: string): string {
  if (!cls) return '(空)'
  const idx = cls.lastIndexOf('.')
  return idx >= 0 ? cls.slice(idx + 1) : cls
}
function shortId(id: string): string {
  if (!id) return ''
  const idx = id.lastIndexOf('/')
  const base = idx >= 0 ? id.slice(idx + 1) : id
  return base.length > 24 ? base.slice(0, 24) + '…' : base
}

async function copyRaw() {
  try {
    await navigator.clipboard.writeText(rawText.value)
    ElMessage.success('已复制回执原文')
  } catch {
    ElMessage.warning('复制失败，请手动选择下方文本')
  }
}

// ---- 复制选择器 / 参数（探查工具核心：一键导出，直接粘进脚本/命令）----

/** 根据节点字段智能生成选择器表达式（优先 id，其次 text/desc，最后 className）。 */
function buildSelector(n: UiNode): string {
  const parts: string[] = []
  if (n.id) parts.push(`id("${n.id}")`)
  else if (n.text) parts.push(`text("${n.text}")`)
  else if (n.desc) parts.push(`desc("${n.desc}")`)
  if (!parts.length && n.className) parts.push(`className("${n.className}")`)
  if (n.clickable) parts.push('clickable()')
  return parts.join('.') || 'text("")'
}

async function copySelector() {
  if (!selected.value) return
  const sel = buildSelector(selected.value)
  try {
    await navigator.clipboard.writeText(sel)
    ElMessage.success(`已复制选择器：${sel}`)
  } catch {
    ElMessage.warning('复制失败，请手动复制')
  }
}

async function copyNodeJson() {
  if (!selected.value) return
  const n = selected.value
  const json = JSON.stringify({
    text: n.text, desc: n.desc, id: n.id, className: n.className, package: n.package,
    bounds: n.bounds, clickable: n.clickable, editable: n.editable, enabled: n.enabled,
  }, null, 2)
  try {
    await navigator.clipboard.writeText(json)
    ElMessage.success('已复制控件参数 JSON')
  } catch {
    ElMessage.warning('复制失败，请手动复制')
  }
}

// 首次挂载即解析（弹窗 destroy-on-close，每次 show 重建）
initTree()
</script>

<style scoped>
.ui-meta {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding-bottom: 12px;
  margin-bottom: 14px;
  border-bottom: 1px solid #ebeef5;
  color: #606266;
  font-size: 12px;
}
.ui-summary {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 12px;
  font-size: 12px;
  color: #606266;
}
.ui-summary-note {
  color: #909399;
  line-height: 1.5;
}
.ui-summary-note code {
  background: #f4f4f5;
  padding: 1px 6px;
  border-radius: 3px;
  color: #c7254e;
  font-size: 12px;
}
.ui-tree-wrap {
  max-height: 460px;
  overflow: auto;
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 6px 0;
  background: #fff;
}
.ui-tree-row {
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 3px 8px 3px 0;
  font-size: 12px;
  min-width: max-content;
}
.ui-tree-row:hover { background: #f5f7fa; }
.ui-tree-toggle {
  width: 16px;
  height: 16px;
  display: inline-flex;
  align-items: center;
  justify-content: center;
  flex-shrink: 0;
  cursor: default;
  color: #909399;
  user-select: none;
}
.ui-tree-arrow {
  cursor: pointer;
  transition: transform 0.15s;
  display: inline-block;
  color: #606266;
  font-size: 12px;
}
.ui-tree-arrow.open { transform: rotate(90deg); }
.ui-tree-dot { color: #dcdfe6; }
.ui-node-label {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}
.ui-node-cls {
  font-weight: 600;
  color: #409eff;
  background: #ecf5ff;
  padding: 1px 6px;
  border-radius: 3px;
  font-size: 11px;
  white-space: nowrap;
}
.ui-node-text {
  color: #303133;
  max-width: 220px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.ui-node-text-empty {
  color: #c0c4cc;
  font-style: italic;
}
.ui-node-id {
  color: #909399;
  font-size: 11px;
  white-space: nowrap;
}
.ui-node-flags {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
.ui-node-bounds {
  color: #c0c4cc;
  font-size: 11px;
  white-space: nowrap;
}
.ui-node-clickable .ui-node-cls { color: #67c23a; }
.ui-find-list {
  max-height: 460px;
  overflow: auto;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.ui-find-card {
  border: 1px solid #ebeef5;
  border-radius: 4px;
  padding: 10px 12px;
  background: #fff;
}
.ui-find-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 6px;
}
.ui-find-index {
  font-weight: 700;
  color: #409eff;
  flex-shrink: 0;
}
.ui-find-text {
  color: #303133;
  font-size: 13px;
  margin-bottom: 8px;
  word-break: break-all;
}
.ui-kv {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 6px 16px;
}
.ui-kv-item {
  display: flex;
  gap: 8px;
  font-size: 12px;
  min-width: 0;
}
.ui-kv-label { color: #909399; flex-shrink: 0; }
.ui-kv-value { color: #303133; word-break: break-all; }
.ui-empty { color: #909399; font-size: 12px; padding: 8px 0; }
.ui-tip-inline {
  margin-top: 6px;
  padding: 8px 10px;
  background: #fdf6ec;
  color: #e6a23c;
  border-radius: 4px;
  font-size: 12px;
}
.ui-raw { margin-top: 18px; }
.ui-raw-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 6px;
}
.ui-raw-body {
  margin: 0;
  padding: 10px 12px;
  max-height: 180px;
  overflow: auto;
  background: #f5f7fa;
  border-radius: 4px;
  color: #606266;
  font-size: 12px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-all;
  user-select: text;
}
.ui-tree-row-active {
  background: #ecf5ff;
}
.ui-tree-row { cursor: pointer; }
.ui-tree-row:hover { background: #f5f7fa; }
.ui-tree-row-active:hover { background: #ecf5ff; }
/* ---- 控件详情抽屉 ---- */
.ui-node-drawer :deep(.el-drawer__body) {
  padding: 0 20px 20px;
}
.ui-drawer-hero {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 12px 0;
  border-bottom: 1px solid #ebeef5;
  margin-bottom: 10px;
}
.ui-drawer-text {
  color: #303133;
  font-size: 14px;
  font-weight: 600;
  word-break: break-all;
}
.ui-drawer-hero-actions {
  margin-left: auto;
  display: flex;
  gap: 6px;
  flex-shrink: 0;
}
.ui-drawer-hint {
  color: #909399;
  font-size: 12px;
  margin-bottom: 8px;
  line-height: 1.5;
}
.ui-drawer-section {
  margin-top: 16px;
}
.ui-drawer-sec-title {
  font-weight: 600;
  color: #303133;
  font-size: 13px;
  margin-bottom: 8px;
  padding-left: 8px;
  border-left: 3px solid #409eff;
}
.ui-drawer-kv {
  display: grid;
  grid-template-columns: 1fr;
  gap: 6px;
}
.ui-drawer-kv-item {
  display: flex;
  gap: 10px;
  font-size: 12px;
  padding: 6px 10px;
  background: #f5f7fa;
  border-radius: 4px;
  min-width: 0;
}
.ui-drawer-kv-item .k {
  color: #909399;
  flex-shrink: 0;
  min-width: 110px;
}
.ui-k-hint {
  border-bottom: 1px dashed #c0c4cc;
  cursor: help;
}
.ui-k-hint:hover {
  color: #409eff;
  border-bottom-color: #409eff;
}
.ui-drawer-sec-tip {
  margin-left: 8px;
  font-weight: 400;
  font-size: 12px;
  color: #c0c4cc;
}
.ui-drawer-kv-item .v {
  color: #303133;
  word-break: break-all;
  user-select: text;
}
.ui-drawer-flags {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.ui-drawer-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}
.ui-drawer-action {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 3px 8px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 12px;
}
.ui-drawer-action-id {
  color: #409eff;
  font-family: monospace;
}
.ui-drawer-action-label {
  color: #606266;
}
</style>
