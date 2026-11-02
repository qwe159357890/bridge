<template>
  <el-dialog v-model="visible" :title="title" width="680px" destroy-on-close class="ssh-detail">
    <template v-if="record">
      <!-- 通用：命令元信息条 -->
      <div class="ssh-meta">
        <el-tag :type="statusTagType" size="small" effect="dark">{{ statusText }}</el-tag>
        <span>ID {{ record.command_id.slice(0, 8) }}…</span>
        <span>设备 {{ deviceLabel }}</span>
        <span>下发 {{ formatTime(record.created_at) }}</span>
        <span v-if="record.result_at">回执 {{ formatTime(record.result_at) }}</span>
      </div>

      <!-- ① ssh_status：状态快照卡 -->
      <template v-if="kind === 'ssh_status'">
        <div class="ssh-hero">
          <el-tag :type="stateTag.type" size="large" effect="dark">{{ stateTag.label }}</el-tag>
          <span class="ssh-hero-note">查询时刻的本地快照，不是实时通道</span>
        </div>
        <div v-if="statusFields.length" class="ssh-kv">
          <div v-for="f in statusFields" :key="f.label" class="ssh-kv-item">
            <span class="ssh-kv-label">{{ f.label }}</span>
            <span class="ssh-kv-value">{{ f.value }}</span>
          </div>
        </div>
        <div v-else class="ssh-empty">该状态无固定字段（未运行 / 启停过渡 / 异常），详情见下方回执原文。</div>
        <div class="ssh-tip">连接密码与 hostkey 指纹只在 APP「设置 → 连接 → SSH 服务」状态页展示，后台不回显。</div>
      </template>

      <!-- ② ssh_start：执行结果解读 + 本次启动信息 -->
      <template v-else-if="kind === 'ssh_start'">
        <div class="ssh-verdict" :class="`ssh-verdict-${startVerdict.type}`">
          <el-tag :type="startVerdict.type" size="small" effect="dark">{{ startVerdict.label }}</el-tag>
          <span>{{ startVerdict.explain }}</span>
        </div>
        <div class="ssh-block-title">本次启动信息</div>
        <div v-if="startFields.length" class="ssh-kv">
          <div v-for="f in startFields" :key="f.label" class="ssh-kv-item">
            <span class="ssh-kv-label">{{ f.label }}</span>
            <span class="ssh-kv-value">{{ f.value }}</span>
          </div>
        </div>
        <div v-else class="ssh-empty">启动未成功，无可解析的启动信息。</div>
        <div class="ssh-tip">建议下一步：{{ startVerdict.next }}</div>
      </template>

      <!-- ③ ssh_stop：停止前状态 + 自恢复意愿影响 -->
      <template v-else-if="kind === 'ssh_stop'">
        <div class="ssh-verdict" :class="`ssh-verdict-${stopVerdict.type}`">
          <el-tag :type="stopVerdict.type" size="small" effect="dark">{{ stopVerdict.label }}</el-tag>
          <span>{{ stopVerdict.explain }}</span>
        </div>
        <div class="ssh-block-title">停止前状态</div>
        <div class="ssh-hero ssh-hero-compact">
          <el-tag :type="stopState.type" size="small">{{ stopState.label }}</el-tag>
          <span class="ssh-hero-note">{{ stopBefore || '（回执中未包含停止前快照）' }}</span>
        </div>
        <div v-if="stopFields.length" class="ssh-kv">
          <div v-for="f in stopFields" :key="f.label" class="ssh-kv-item">
            <span class="ssh-kv-label">{{ f.label }}</span>
            <span class="ssh-kv-value">{{ f.value }}</span>
          </div>
        </div>
        <div class="ssh-block-title">执行影响</div>
        <div class="ssh-kv">
          <div class="ssh-kv-item">
            <span class="ssh-kv-label">是否执行停止</span>
            <span class="ssh-kv-value">{{ stopExecuted ? '是（已断开全部活动会话）' : '否（设备端守卫忽略重复命令）' }}</span>
          </div>
          <div class="ssh-kv-item">
            <span class="ssh-kv-label">自恢复意愿（开机 / 冷启动 / STICKY 重建自动拉起）</span>
            <span class="ssh-kv-value">
              <el-tag :type="stopMemory.type" size="small">{{ stopMemory.label }}</el-tag>
            </span>
          </div>
        </div>
        <div class="ssh-tip">{{ stopMemory.desc }}</div>
      </template>

      <!-- 通用：回执原文 -->
      <div class="ssh-raw">
        <div class="ssh-raw-head">
          <span>回执原文</span>
          <el-button size="small" link type="primary" @click="copyRaw">复制</el-button>
        </div>
        <pre class="ssh-raw-body">{{ rawText }}</pre>
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
 * SSH 三条命令的「查看详情」弹窗（命令下发页 · 命令历史回执列）。
 *
 * 设计要点（用户 2026-11-02 拍板，与 VNT 弹窗同款分治约定）：每条命令的详情
 * 都要单独设计，展示不同的参数、不同的数据、不同的布局，不做成通用回执文本框。
 * 数据来源仅限现有 CommandRecord（params + result_message），不改 APK / 客户端：
 *   ① ssh_status → 解析状态快照成 KV 网格（端口 / 用户名 / 活动会话 / 存储暴露）
 *   ② ssh_start  → 结果分类解读 + 从回执解析端口 / 用户名 / 存储暴露（命令本身无参数）
 *   ③ ssh_stop   → 停止前状态 + 是否真正停止 + 自恢复意愿（enabled）影响
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
    case 'ssh_status': return 'SSH 服务状态 · 查看详情'
    case 'ssh_start': return '启动 SSH 服务 · 执行详情'
    case 'ssh_stop': return '停止 SSH 服务 · 执行详情'
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

/** 解析 SshServerController.statusText() 的固定格式；解析不出的字段直接不显示。 */
function parseStatusFields(msg: string): Field[] {
  const out: Field[] = []
  const push = (label: string, re: RegExp) => {
    const m = msg.match(re)
    if (m) out.push({ label, value: m[1].trim() })
  }
  push('端口', /端口\s+(\d+)/)
  push('用户名', /用户名\s+(\S+)/)
  push('活动会话数', /活动会话\s+(\d+)/)
  push('存储暴露', /存储暴露\s+([^（\s]+(?:（[^）]+）)?)/)
  return out
}

/** 状态标签：与命令下发页 parseSshStatus 的口径保持一致（客户端 statusText 固定前缀）。 */
function stateTagOf(msg: string): StateTag {
  const m = msg || ''
  if (m.startsWith('运行中 ·')) return { label: '运行中', type: 'success' }
  if (m.startsWith('正在启动')) return { label: '正在启动', type: 'warning' }
  if (m.startsWith('正在停止')) return { label: '正在停止', type: 'warning' }
  if (m.startsWith('未运行')) return { label: '未运行', type: 'info' }
  if (m.startsWith('异常：') || m.startsWith('执行失败')) return { label: '异常', type: 'danger' }
  return { label: '未知', type: 'info' }
}

const msg = computed(() => record.value?.result_message ?? '')
const stateTag = computed(() => stateTagOf(msg.value))
const statusFields = computed(() => parseStatusFields(msg.value))

// ---- ② ssh_start（命令本身无参数，信息从回执解析）----
const startFields = computed<Field[]>(() => {
  const m = msg.value
  if (!m.startsWith('已下发启动 SSH 服务')) return []
  const out: Field[] = []
  const push = (label: string, re: RegExp) => {
    const hit = m.match(re)
    if (hit) out.push({ label, value: hit[1].trim() })
  }
  push('端口', /端口\s+(\d+)/)
  push('用户名', /用户名\s+(\S+)/)
  push('存储暴露', /存储暴露\s+([^；\s]+)/)
  push('连接示例', /(ssh -p\s+\S+)/)
  return out
})

const startVerdict = computed<Verdict>(() => {
  const m = msg.value
  if (m.startsWith('已下发启动 SSH 服务')) {
    return {
      label: '已下发启动', type: 'success',
      explain: '命令已被设备接受，内嵌 SSH 服务器（MINA SSHD）正在启动。',
      next: '用「SSH 服务状态」确认为「运行中」后即可 SFTP / 终端连接。',
    }
  }
  if (m.startsWith('重复下发被忽略：SSH 服务已在运行')) {
    return {
      label: '重复下发被忽略', type: 'warning',
      explain: '设备端守卫已拦截：SSH 服务本就在运行，本次命令未生效。',
      next: '如需变更端口等配置，请先下发「停止 SSH 服务」再重新启动。',
    }
  }
  if (m.startsWith('重复下发被忽略：正在停止')) {
    return {
      label: '停止中，暂不处理', type: 'warning',
      explain: '设备正在停止上一次 SSH 服务，本次启动命令被忽略。',
      next: '稍后用「SSH 服务状态」确认为「未运行」后再下发。',
    }
  }
  if (m.startsWith('启动失败')) {
    return {
      label: '启动失败', type: 'danger',
      explain: '多为 Android 12+ 后台启动前台服务受限。',
      next: '让 App 回到前台再试，或由用户在手机上点一次开关。',
    }
  }
  return {
    label: '执行失败', type: 'danger',
    explain: m || '设备未返回可读原因。',
    next: '查看设备日志，或确认设备在线后重试。',
  }
})

// ---- ③ ssh_stop ----
const stopBefore = computed(() => {
  const m = msg.value
  const hit = m.match(/停止前：(.+?)）/)
  return hit ? hit[1].trim() : ''
})
const stopState = computed(() => stateTagOf(stopBefore.value || msg.value))
const stopFields = computed(() => (stopBefore.value ? parseStatusFields(stopBefore.value) : []))
const stopExecuted = computed(() => !msg.value.includes('未执行停止'))
const stopVerdict = computed<Verdict>(() => {
  if (msg.value.startsWith('已下发停止 SSH 服务')) {
    return {
      label: '已下发停止', type: 'success',
      explain: '停止指令已送达，sshd 正在关闭、全部活动会话将被断开。',
      next: '稍后用「SSH 服务状态」确认为「未运行」。',
    }
  }
  if (msg.value.startsWith('重复下发被忽略')) {
    return {
      label: '重复下发被忽略', type: 'warning',
      explain: '设备当前未运行 SSH 服务，未执行停止、自恢复意愿保持不变。',
      next: '若后台状态与实际不符，请点「刷新状态」重新探测。',
    }
  }
  return {
    label: '执行失败', type: 'danger', explain: msg.value || '设备未返回可读原因。',
    next: '查看设备日志，或确认设备在线后重试。',
  }
})
const stopMemory = computed<{ label: string; type: TagType; desc: string }>(() => {
  if (msg.value.includes('清除自恢复意愿')) {
    return {
      label: '已清除', type: 'warning',
      desc: '下次开机 / 冷启动 / 进程重建都不会自动拉起 SSH 服务。如需恢复，请在 APP 里重新打开开关或下发「启动 SSH 服务」写回意愿。',
    }
  }
  if (msg.value.includes('未执行停止')) {
    return {
      label: '保持不变', type: 'info',
      desc: '设备端守卫忽略了重复命令，自恢复意愿未被改动，自愈行为不变。',
    }
  }
  return { label: '未知', type: 'info', desc: '回执未包含意愿变更说明，以回执原文为准。' }
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
.ssh-meta {
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
.ssh-hero {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 14px;
}
.ssh-hero-compact { margin: 6px 0 12px; }
.ssh-hero-note { color: #909399; font-size: 12px; line-height: 1.5; }
.ssh-block-title {
  font-weight: 600;
  color: #303133;
  margin: 14px 0 8px;
  font-size: 13px;
}
.ssh-kv {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
}
.ssh-kv-item {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  padding: 7px 10px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 12px;
  min-width: 0;
}
.ssh-kv-label { color: #909399; flex-shrink: 0; }
.ssh-kv-value { color: #303133; word-break: break-all; }
.ssh-verdict {
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
.ssh-verdict-success { background: #f0f9eb; border-left-color: #67c23a; }
.ssh-verdict-warning { background: #fdf6ec; border-left-color: #e6a23c; }
.ssh-verdict-danger { background: #fef0f0; border-left-color: #f56c6c; }
.ssh-verdict-info { background: #f4f4f5; border-left-color: #909399; }
.ssh-tip {
  margin-top: 12px;
  padding: 8px 10px;
  background: #ecf5ff;
  color: #606266;
  font-size: 12px;
  line-height: 1.6;
  border-radius: 4px;
}
.ssh-empty { color: #909399; font-size: 12px; padding: 8px 0; }
.ssh-raw { margin-top: 18px; }
.ssh-raw-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 6px;
}
.ssh-raw-body {
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
