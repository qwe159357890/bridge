<template>
  <el-dialog v-model="visible" :title="title" width="640px" destroy-on-close class="devstate-detail">
    <template v-if="record">
      <!-- 通用：命令元信息条 -->
      <div class="devstate-meta">
        <el-tag :type="statusTagType" size="small" effect="dark">{{ statusText }}</el-tag>
        <span>ID {{ record.command_id.slice(0, 8) }}…</span>
        <span>设备 {{ deviceLabel }}</span>
        <span>下发 {{ formatTime(record.created_at) }}</span>
        <span v-if="record.result_at">回执 {{ formatTime(record.result_at) }}</span>
      </div>

      <!-- 核心结论：该维度对「锁屏播报音量提升」的贡献 -->
      <div class="devstate-verdict" :class="`devstate-verdict-${verdict.type}`">
        <span class="devstate-verdict-icon">{{ verdict.icon }}</span>
        <div class="devstate-verdict-body">
          <div class="devstate-verdict-title">{{ verdict.title }}</div>
          <div class="devstate-verdict-explain">{{ verdict.explain }}</div>
        </div>
      </div>

      <!-- 结构化字段网格 -->
      <div class="devstate-block-title">状态明细</div>
      <div class="devstate-kv">
        <div v-for="f in fields" :key="f.label" class="devstate-kv-item">
          <span class="devstate-kv-label">{{ f.label }}</span>
          <span class="devstate-kv-value">
            <el-tag :type="f.tagType" size="small">{{ f.value }}</el-tag>
          </span>
        </div>
      </div>

      <!-- 通用：回执原文 -->
      <div class="devstate-raw">
        <div class="devstate-raw-head">
          <span>回执原文</span>
          <el-button size="small" link type="primary" @click="copyRaw">复制</el-button>
        </div>
        <pre class="devstate-raw-body">{{ rawText }}</pre>
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
 * get_lock_state / get_screen_state 两条命令的「查看详情」弹窗（命令下发页 · 命令历史回执列）。
 *
 * 设计要点（沿用 VNT/SSH/UI/电流量同款「按命令分治」约定，2026-11-08 用户需求）：
 * 从现有 CommandRecord 的 result_message 解析锁屏 / 息屏布尔字段，渲染结构化弹窗，
 * 并单独给出「该维度对锁屏播报音量提升（shouldBoost = 锁定 || 熄屏）的贡献」结论，
 * 帮用户一眼看懂这条状态会不会触发音量拉满。
 *
 * 回执格式（客户端 SpeakVolumeBoost.describeLock / describeScreen 固定）：
 *   锁屏状态：device_locked=true（已锁定），keyguard_showing=false（锁屏界面未显示）
 *   屏幕状态：screen_on=false（息屏中）
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
interface Field { label: string; value: string; tagType: TagType }
interface Verdict { icon: string; title: string; explain: string; type: TagType }

const kind = computed(() => record.value?.command ?? '')
const rawText = computed(() => record.value?.result_message || '（暂无回执）')
const deviceLabel = computed(() => {
  const id = record.value?.device_id ?? ''
  return props.deviceNames?.[id] || id
})
const title = computed(() =>
  kind.value === 'get_screen_state' ? '息屏状态 · 查看详情' : '锁屏状态 · 查看详情',
)
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

const msg = computed(() => record.value?.result_message || '')

/** 从回执提取布尔字段（形如 key=true / key=false）。 */
function boolOf(key: string): boolean | null {
  const m = msg.value.match(new RegExp(`${key}=(true|false)`))
  return m ? m[1] === 'true' : null
}

/** 结构化字段网格。 */
const fields = computed<Field[]>(() => {
  if (kind.value === 'get_screen_state') {
    const on = boolOf('screen_on')
    if (on === null) return []
    return [{
      label: '屏幕是否点亮',
      value: on ? '亮屏中' : '息屏中',
      tagType: on ? 'warning' : 'info',
    }]
  }
  // get_lock_state（默认）
  const locked = boolOf('device_locked')
  const showing = boolOf('keyguard_showing')
  const out: Field[] = []
  if (locked !== null) {
    out.push({
      label: '是否锁定（需解锁）',
      value: locked ? '已锁定' : '未锁定',
      tagType: locked ? 'danger' : 'success',
    })
  }
  if (showing !== null) {
    out.push({
      label: '锁屏界面显示中',
      value: showing ? '显示中' : '未显示',
      tagType: showing ? 'warning' : 'info',
    })
  }
  return out
})

/** 核心结论：shouldBoost = 锁定 || 熄屏，本命令只覆盖其中一个维度。 */
const verdict = computed<Verdict>(() => {
  if (kind.value === 'get_screen_state') {
    const on = boolOf('screen_on')
    if (on === null) return { icon: '❓', title: '未能解析', explain: '回执中未包含 screen_on 字段，详情以回执原文为准。', type: 'info' }
    return on
      ? {
          icon: '🔕',
          title: '屏幕点亮：不触发音量提升',
          explain: '亮屏中说明用户可能正在主动用手机，播报时尊重当前音量设置，不会临时拉满。若此时设备已锁定（锁屏界面），仍会触发——可用「查询锁屏状态」确认另一维度。',
          type: 'info',
        }
      : {
          icon: '🔊',
          title: '屏幕熄灭：会触发音量提升',
          explain: '熄屏（含无安全锁 + 熄屏）属于后台静默状态，播报前会把媒体音量临时拉满、播完恢复。',
          type: 'success',
        }
  }
  // get_lock_state
  const locked = boolOf('device_locked')
  if (locked === null) return { icon: '❓', title: '未能解析', explain: '回执中未包含 device_locked 字段，详情以回执原文为准。', type: 'info' }
  return locked
    ? {
        icon: '🔊',
        title: '设备锁定：会触发音量提升',
        explain: '已锁定（设了安全锁且未解锁）属于后台静默状态，播报前会把媒体音量临时拉满、播完恢复。',
        type: 'success',
      }
    : {
        icon: '🔕',
        title: '设备未锁定：不触发音量提升',
        explain: '未锁定（无安全锁，或已解锁）时，仅凭本维度不触发；若屏幕也熄灭（息屏）仍会触发——可用「查询息屏状态」确认另一维度。',
        type: 'info',
      }
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
.devstate-meta {
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
.devstate-verdict {
  display: flex;
  align-items: flex-start;
  gap: 12px;
  padding: 14px 16px;
  border-radius: 6px;
  border-left: 4px solid #909399;
  background: #f4f4f5;
}
.devstate-verdict-success { background: #f0f9eb; border-left-color: #67c23a; }
.devstate-verdict-warning { background: #fdf6ec; border-left-color: #e6a23c; }
.devstate-verdict-danger { background: #fef0f0; border-left-color: #f56c6c; }
.devstate-verdict-info { background: #f4f4f5; border-left-color: #909399; }
.devstate-verdict-icon { font-size: 22px; line-height: 1.2; }
.devstate-verdict-body { min-width: 0; }
.devstate-verdict-title { font-size: 14px; font-weight: 600; color: #303133; margin-bottom: 4px; }
.devstate-verdict-explain { font-size: 12px; color: #606266; line-height: 1.6; }
.devstate-block-title {
  font-weight: 600;
  color: #303133;
  margin: 16px 0 8px;
  font-size: 13px;
}
.devstate-kv {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px 16px;
}
.devstate-kv-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 9px 12px;
  background: #f5f7fa;
  border-radius: 4px;
  font-size: 12px;
  min-width: 0;
}
.devstate-kv-label { color: #909399; flex-shrink: 0; }
.devstate-kv-value { color: #303133; }
.devstate-raw { margin-top: 18px; }
.devstate-raw-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  font-size: 13px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 6px;
}
.devstate-raw-body {
  margin: 0;
  padding: 10px 12px;
  max-height: 160px;
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
