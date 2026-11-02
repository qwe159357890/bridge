<template>
  <el-dialog v-model="visible" :title="title" width="680px" destroy-on-close class="vnt-detail">
    <template v-if="record">
      <!-- 通用：命令元信息条 -->
      <div class="vnt-meta">
        <el-tag :type="statusTagType" size="small" effect="dark">{{ statusText }}</el-tag>
        <span>ID {{ record.command_id.slice(0, 8) }}…</span>
        <span>设备 {{ deviceLabel }}</span>
        <span>下发 {{ formatTime(record.created_at) }}</span>
        <span v-if="record.result_at">回执 {{ formatTime(record.result_at) }}</span>
      </div>

      <!-- ① vnt_status：状态快照卡 -->
      <template v-if="kind === 'vnt_status'">
        <div class="vnt-hero">
          <el-tag :type="stateTag.type" size="large" effect="dark">{{ stateTag.label }}</el-tag>
          <span class="vnt-hero-note">查询时刻的本地快照，不是实时通道</span>
        </div>
        <div v-if="statusFields.length" class="vnt-kv">
          <div v-for="f in statusFields" :key="f.label" class="vnt-kv-item">
            <span class="vnt-kv-label">{{ f.label }}</span>
            <span class="vnt-kv-value">{{ f.value }}</span>
          </div>
        </div>
        <div v-else class="vnt-empty">该回执无固定字段（未连接 / 连接中 / 异常），详情见下方回执原文。</div>
        <div class="vnt-tip">详细设备列表需打开 App「组网页」查看；本命令只读本地缓存，不主动拉列表。</div>
      </template>

      <!-- ② vnt_start：本次下发配置 + 执行结果 -->
      <template v-else-if="kind === 'vnt_start'">
        <div class="vnt-verdict" :class="`vnt-verdict-${startVerdict.type}`">
          <el-tag :type="startVerdict.type" size="small" effect="dark">{{ startVerdict.label }}</el-tag>
          <span>{{ startVerdict.explain }}</span>
        </div>
        <div class="vnt-block-title">本次下发参数</div>
        <div class="vnt-kv">
          <div v-for="f in startParams" :key="f.label" class="vnt-kv-item">
            <span class="vnt-kv-label">{{ f.label }}</span>
            <span class="vnt-kv-value">{{ f.value }}</span>
          </div>
        </div>
        <div class="vnt-tip">建议下一步：{{ startVerdict.next }}</div>
      </template>

      <!-- ③ vnt_stop：停止前状态 + 记忆影响 -->
      <template v-else-if="kind === 'vnt_stop'">
        <div class="vnt-verdict" :class="`vnt-verdict-${stopVerdict.type}`">
          <el-tag :type="stopVerdict.type" size="small" effect="dark">{{ stopVerdict.label }}</el-tag>
          <span>{{ stopVerdict.explain }}</span>
        </div>
        <div class="vnt-block-title">停止前状态</div>
        <div class="vnt-hero vnt-hero-compact">
          <el-tag :type="stopState.type" size="small">{{ stopState.label }}</el-tag>
          <span class="vnt-hero-note">{{ stopBefore || '（回执中未包含停止前快照）' }}</span>
        </div>
        <div v-if="stopFields.length" class="vnt-kv">
          <div v-for="f in stopFields" :key="f.label" class="vnt-kv-item">
            <span class="vnt-kv-label">{{ f.label }}</span>
            <span class="vnt-kv-value">{{ f.value }}</span>
          </div>
        </div>
        <div class="vnt-block-title">执行影响</div>
        <div class="vnt-kv">
          <div class="vnt-kv-item">
            <span class="vnt-kv-label">是否执行停止</span>
            <span class="vnt-kv-value">{{ stopExecuted ? '是' : '否（设备端守卫忽略重复命令）' }}</span>
          </div>
          <div class="vnt-kv-item">
            <span class="vnt-kv-label">开机自动组网记忆</span>
            <span class="vnt-kv-value">
              <el-tag :type="stopMemory.type" size="small">{{ stopMemory.label }}</el-tag>
            </span>
          </div>
        </div>
        <div class="vnt-tip">{{ stopMemory.desc }}</div>
      </template>

      <!-- 通用：回执原文 -->
      <div class="vnt-raw">
        <div class="vnt-raw-head">
          <span>回执原文</span>
          <el-button size="small" link type="primary" @click="copyRaw">复制</el-button>
        </div>
        <pre class="vnt-raw-body">{{ rawText }}</pre>
      </div>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, ref } from 'vue'
import { ElMessage } from 'element-plus'
import type { CommandRecord } from '@/types'
import { formatTime } from '@/utils/format'

/**
 * VNT 三条命令的「查看详情」弹窗（命令下发页 · 命令历史回执列）。
 *
 * 设计要点（用户 2026-11-02 对时确认）：每条命令的详情都要单独设计，
 * 展示不同的参数、不同的数据、不同的布局，不做成通用回执文本框。
 * 数据来源仅限现有 CommandRecord（params + result_message），不改 APK / 客户端：
 *   ① vnt_status → 解析状态快照成 KV 网格（虚拟 IP / Token / 服务端 / 协议 / 在线设备 / 版本）
 *   ② vnt_start  → 本次下发参数（密码脱敏）+ 结果分类解读与下一步建议
 *   ③ vnt_stop   → 停止前状态 + 是否真正停止 + 开机自动组网记忆影响
 */
const props = defineProps<{ deviceNames?: Record<string, string> }>()

const visible = ref(false)
const record = ref<CommandRecord | null>(null)

function show(row: CommandRecord) {
  record.value = row
  visible.value = true
}
defineExpose({ show })

type TagType = 'success' | 'warning' | 'danger' | 'info'
interface Field { label: string; value: string }
interface StateTag { label: string; type: TagType }
interface Verdict { label: string; type: TagType; explain: string; next: string }

const kind = computed(() => record.value?.command ?? '')
const rawText = computed(() => record.value?.result_message || '（暂无回执）')
const deviceLabel = computed(() => {
  const id = record.value?.device_id ?? ''
  return props.deviceNames?.[id] || id
})
const title = computed(() => {
  switch (kind.value) {
    case 'vnt_status': return '组网状态 · 查看详情'
    case 'vnt_start': return '开始组网 · 执行详情'
    case 'vnt_stop': return '停止组网 · 执行详情'
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

/** 解析 VntMeshController.statusText() 的固定格式；解析不出的字段直接不显示。 */
function parseStatusFields(msg: string): Field[] {
  const out: Field[] = []
  const push = (label: string, re: RegExp) => {
    const m = msg.match(re)
    if (m) out.push({ label, value: m[1].trim() })
  }
  push('虚拟 IP', /虚拟IP\s+([^\s·]+)/)
  push('组网编号 Token', /Token\s+([^\s·]+)/)
  const srv = msg.match(/服务端\s+([^（\s]+)（(TCP|UDP)）/)
  if (srv) {
    out.push({ label: '服务端地址', value: srv[1] })
    out.push({ label: '连接协议', value: srv[2] })
  }
  push('在线设备数', /在线设备\s+(\d+)/)
  push('服务端版本', /服务端版本\s+([^\s（]+)/)
  return out
}

/** 状态标签：与命令下发页 parseVntStatus 的口径保持一致。 */
function stateTagOf(msg: string): StateTag {
  const m = msg || ''
  if (m.startsWith('运行中 ·')) return { label: '运行中', type: 'success' }
  if (m.startsWith('正在建立连接')) return { label: '正在建立连接', type: 'warning' }
  if (m.startsWith('正在停止')) return { label: '正在停止', type: 'warning' }
  if (m.startsWith('未连接')) return { label: '未连接', type: 'info' }
  if (m.startsWith('异常：') || m.startsWith('执行失败')) return { label: '异常', type: 'danger' }
  return { label: '未知', type: 'info' }
}

const msg = computed(() => record.value?.result_message ?? '')
const stateTag = computed(() => stateTagOf(msg.value))
const statusFields = computed(() => parseStatusFields(msg.value))

// ---- ② vnt_start ----
const startParams = computed<Field[]>(() => {
  const p = record.value?.params ?? {}
  const tcpText = String(p.tcp ?? '').toLowerCase()
  let proto = '沿用 App 内保存配置（默认 UDP）'
  if (['true', '1', 'tcp'].includes(tcpText)) proto = 'TCP'
  else if (['false', '0', 'udp'].includes(tcpText)) proto = 'UDP'
  const token = p.token || p.network_code
  return [
    { label: '服务端地址', value: p.server ? String(p.server) : '沿用 App 内保存配置' },
    { label: '组网编号 Token', value: token ? String(token) : '沿用 App 内保存配置' },
    { label: '客户端加密密码', value: p.password ? '已设置（••••，不回显明文）' : '未设置 / 沿用 App 内保存配置' },
    { label: '连接协议', value: proto },
  ]
})

const startVerdict = computed<Verdict>(() => {
  const m = msg.value
  if (m.startsWith('已下发开始组网')) {
    return {
      label: '已下发', type: 'success',
      explain: '命令已被设备接受，正在建立 VNT 连接。',
      next: '首次需等待服务器注册，可用「组网状态」查询；切 Tab / 退后台不断连。',
    }
  }
  if (m.startsWith('重复下发被忽略：组网已在运行')) {
    return {
      label: '重复下发被忽略', type: 'warning',
      explain: '设备端守卫已拦截：本次参数未生效，自动组网记忆也未改动。',
      next: '如需变更配置，请先下发「停止组网」再重新「开始组网」。',
    }
  }
  if (m.startsWith('重复下发被忽略：正在停止')) {
    return {
      label: '停止中，暂不处理', type: 'warning',
      explain: '设备正在停止上一次组网，本次开始命令被忽略。',
      next: '稍后用「组网状态」确认为「未连接」后再下发。',
    }
  }
  if (m.startsWith('启动失败')) {
    return {
      label: '启动失败', type: 'danger',
      explain: '多为 Android 12+ 后台启动前台服务受限。',
      next: '让 App 回到前台再试，或由用户在手机上点一次「开始组网」。',
    }
  }
  if (m.startsWith('组网编号（Token）为空')) {
    return { label: '缺少 Token', type: 'danger', explain: m, next: '在 App「组网配置」填写，或用本命令 token 参数指定。' }
  }
  if (m.startsWith('服务端地址非法')) {
    return { label: '地址非法', type: 'danger', explain: m, next: '1.x 只接受裸 host:port，不要带 udp:// / tcp:// 前缀。' }
  }
  if (m.startsWith('尚未授予系统 VPN 权限')) {
    return { label: '未授权 VPN', type: 'danger', explain: '系统 VPN 授权尚未完成，后台无法静默拉起。', next: '需 App 在前台由用户点一次「开始组网」完成授权。' }
  }
  return {
    label: '执行失败', type: 'danger',
    explain: m || '设备未返回可读原因。',
    next: '查看设备日志，或确认设备在线后重试。',
  }
})

// ---- ③ vnt_stop ----
const stopBefore = computed(() => {
  const m = msg.value
  let hit = m.match(/停止前：(.+?)）；/)
  if (!hit) hit = m.match(/当前未组网（(.+?)）/)
  return hit ? hit[1].trim() : ''
})
const stopState = computed(() => stateTagOf(stopBefore.value || msg.value))
const stopFields = computed(() => (stopBefore.value ? parseStatusFields(stopBefore.value) : []))
const stopExecuted = computed(() => !msg.value.includes('未执行停止'))
const stopVerdict = computed<Verdict>(() => {
  if (msg.value.startsWith('已下发停止组网')) {
    return {
      label: '已下发停止', type: 'success',
      explain: '停止指令已送达，VPN 通道释放中。',
      next: '稍后用「组网状态」确认；已清除开机自动组网记忆。',
    }
  }
  if (msg.value.startsWith('重复下发被忽略')) {
    return {
      label: '重复下发被忽略', type: 'warning',
      explain: '设备当前未组网，未执行停止、自动组网记忆保持不变。',
      next: '若后台状态与实际不符，请点「刷新状态」重新探测。',
    }
  }
  return {
    label: '执行失败', type: 'danger', explain: msg.value || '设备未返回可读原因。',
    next: '查看设备日志，或确认设备在线后重试。',
  }
})
const stopMemory = computed<{ label: string; type: TagType; desc: string }>(() => {
  if (msg.value.includes('已清除开机自动组网记忆')) {
    return {
      label: '已清除', type: 'warning',
      desc: '下次冷启动不会自动组网。如需恢复常驻可达，请在 App 里重新点一次「开始组网」写回记忆。',
    }
  }
  if (msg.value.includes('未执行停止')) {
    return {
      label: '保持不变', type: 'info',
      desc: '设备端守卫忽略了重复命令，自动组网记忆未被改动，冷启动行为不变。',
    }
  }
  return { label: '未知', type: 'info', desc: '回执未包含记忆变更说明，以回执原文为准。' }
})

async function copyRaw() {
  try {
    await navigator.clipboard.writeText(rawText.value)
    ElMessage.success('已复制回执原文')
  } catch {
    ElMessage.warning('复制失败，请手动选择下方文本')
  }
}
</script>

<style scoped>
.vnt-meta {
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
.vnt-hero {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}
.vnt-hero-compact { margin: 6px 0 12px; }
.vnt-hero-note { color: #909399; font-size: 12px; line-height: 1.5; }
.vnt-block-title {
  font-weight: 600;
  color: #303133;
  margin: 14px 0 8px;
  font-size: 13px;
}
.vnt-kv {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
}
.vnt-kv-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 7px 10px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 12px;
  min-width: 0;
}
.vnt-kv-label { color: #909399; flex-shrink: 0; }
.vnt-kv-value { color: #303133; word-break: break-all; }
.vnt-verdict {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 12px;
  border-radius: 4px;
  font-size: 13px;
  color: #303133;
  background: #f4f4f5;
  border-left: 3px solid #909399;
}
.vnt-verdict-success { background: #f0f9eb; border-left-color: #67c23a; }
.vnt-verdict-warning { background: #fdf6ec; border-left-color: #e6a23c; }
.vnt-verdict-danger { background: #fef0f0; border-left-color: #f56c6c; }
.vnt-verdict-info { background: #f4f4f5; border-left-color: #909399; }
.vnt-tip {
  margin-top: 12px;
  padding: 8px 10px;
  background: #ecf5ff;
  color: #606266;
  font-size: 12px;
  line-height: 1.6;
  border-radius: 4px;
}
.vnt-empty { color: #909399; font-size: 12px; padding: 8px 0; }
.vnt-raw { margin-top: 18px; }
.vnt-raw-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 6px;
}
.vnt-raw-body {
  margin: 0;
  padding: 10px 12px;
  max-height: 200px;
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
</style>
