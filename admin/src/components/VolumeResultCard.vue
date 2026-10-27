<template>
  <el-card v-if="visible" class="vol-card">
    <template #header>
      <div class="vol-header">
        <span class="title">🎚️ 音量控制{{ deviceName ? ` - ${deviceName}` : '' }}</span>
        <div class="vol-header-right">
          <el-tag v-if="ringer" size="small" type="info">铃声模式：{{ ringer }}</el-tag>
          <el-tag v-if="dnd" size="small" type="warning">免打扰开启</el-tag>
          <el-button :icon="Close" size="small" circle title="关闭" @click="hide" />
        </div>
      </div>
    </template>

    <div v-for="s in streams" :key="s.key" class="vol-row">
      <span class="vol-name">{{ s.name }}</span>
      <el-slider
        v-model="s.pct"
        :disabled="applying"
        :max="100"
        class="vol-slider"
        @change="(v: number) => apply(s, v)"
      />
      <span class="vol-value">{{ s.current }}/{{ s.max }}（{{ s.pct }}%）</span>
    </div>

    <div class="vol-tip">
      拖动滑条松手即按百分比下发「调节音量」；部分 ROM 铃声与通知联动（改一个另一个跟着变）。
      免打扰开启时铃声/通知流可能被系统拒绝，以命令回执为准。
    </div>
  </el-card>
</template>

<script setup lang="ts">
import { ref } from 'vue'
import { Close } from '@element-plus/icons-vue'
import { ElMessage } from 'element-plus'
import { sendCommand } from '@/api/commands'
import { parseVolumeMessage, type VolumeStreamSnapshot } from '@/utils/volume'

/**
 * 音量控制卡片（命令下发页用）。
 *
 * 数据流：父页 WS 收到 get_volume 成功回执 → [show]/[onResult] 传回执文本 →
 * 本组件解析快照渲染 5 条滑条；拖动松手即反向下发 set_volume，并延时重发
 * get_volume 刷新（回执文本经父页 WS 再次流入）。
 */

const visible = ref(false)
const deviceName = ref('')
const deviceId = ref('')
const ringer = ref('')
const dnd = ref(false)
const applying = ref(false)
const streams = ref<VolumeStreamSnapshot[]>([])

defineExpose({ show, onResult, hide })

/** 展示卡片并解析一条 get_volume 回执文本。 */
function show(id: string, name: string, message: string) {
  deviceId.value = id
  deviceName.value = name
  visible.value = true
  onResult(message)
}

/** 解析 get_volume 回执文本，刷新滑条。 */
function onResult(message: string) {
  if (!visible.value) return
  const snap = parseVolumeMessage(message)
  if (snap) {
    streams.value = snap.streams
    ringer.value = snap.ringer
    dnd.value = snap.dnd
  }
}

/** 滑条松手：按百分比下发 set_volume，随后重发 get_volume 刷新真实档位。 */
async function apply(s: VolumeStreamSnapshot, pct: number) {
  if (applying.value) return
  applying.value = true
  try {
    await sendCommand(deviceId.value, 'set_volume', { stream: s.key, level: pct })
    ElMessage.success(`已下发：${s.name} 音量 ${pct}%`)
  } catch {
    // 错误已由 axios 拦截器统一提示
  } finally {
    applying.value = false
    // 上报/设置/回执需要 1~2 秒，之后重发 get_volume 拉真实档位刷新滑条
    setTimeout(() => {
      sendCommand(deviceId.value, 'get_volume', {}).catch(() => {})
    }, 1500)
  }
}

function hide() {
  visible.value = false
}
</script>

<style scoped>
.vol-card {
  margin-top: 16px;
}
.vol-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.vol-header .title {
  font-weight: 600;
}
.vol-header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}
.vol-row {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 4px 0;
}
.vol-name {
  width: 48px;
  font-size: 13px;
  color: #303133;
  flex-shrink: 0;
}
.vol-slider {
  flex: 1;
}
.vol-value {
  width: 120px;
  font-size: 12px;
  color: #606266;
  text-align: right;
  flex-shrink: 0;
}
.vol-tip {
  font-size: 12px;
  color: #909399;
  line-height: 1.5;
  margin-top: 8px;
}
</style>
