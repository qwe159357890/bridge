<template>
  <div>
    <!-- 统计概览 -->
    <el-row :gutter="16" class="stats-row">
      <el-col :span="8">
        <el-card shadow="never" class="stat-card">
          <div class="stat">
            <div class="stat-icon" style="background: rgba(64, 158, 255, 0.12); color: var(--brand)">
              <el-icon><Monitor /></el-icon>
            </div>
            <div>
              <div class="stat-number">{{ devices.length }}</div>
              <div class="stat-label">设备总数</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="stat-card">
          <div class="stat">
            <div class="stat-icon" style="background: rgba(103, 194, 58, 0.12); color: var(--success)">
              <el-icon><Connection /></el-icon>
            </div>
            <div>
              <div class="stat-number">{{ onlineCount }}</div>
              <div class="stat-label">在线设备</div>
            </div>
          </div>
        </el-card>
      </el-col>
      <el-col :span="8">
        <el-card shadow="never" class="stat-card">
          <div class="stat">
            <div class="stat-icon" style="background: rgba(144, 147, 153, 0.12); color: var(--info)">
              <el-icon><CircleClose /></el-icon>
            </div>
            <div>
              <div class="stat-number">{{ offlineCount }}</div>
              <div class="stat-label">离线设备</div>
            </div>
          </div>
        </el-card>
      </el-col>
    </el-row>

    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="title">设备列表</span>
          <div class="header-actions">
            <el-input
              v-model="keyword"
              placeholder="按昵称或设备 ID 搜索"
              clearable
              class="search"
              :prefix-icon="Search"
            />
            <el-button type="primary" :icon="Refresh" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-table :data="filteredDevices" v-loading="loading" stripe>
        <el-table-column label="设备昵称" min-width="160">
          <template #default="{ row }">
            <span class="device-name">{{ row.device_name }}</span>
          </template>
        </el-table-column>
        <el-table-column prop="device_id" label="设备 ID" min-width="220" show-overflow-tooltip />
        <el-table-column label="在线状态" width="110">
          <template #default="{ row }">
            <DeviceStatusTag :online="row.online" />
          </template>
        </el-table-column>
        <el-table-column label="注册时间" width="180">
          <template #default="{ row }">
            {{ formatTime(row.created_at) }}
          </template>
        </el-table-column>
        <el-table-column label="操作" width="430" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="goCommand(row)">
              下发命令
            </el-button>
            <el-button size="small" @click="showLocation(row)">位置</el-button>
            <el-button size="small" @click="goLog(row)">查看日志</el-button>
            <el-button
              size="small"
              type="success"
              plain
              :disabled="!row.online"
              @click="handleAdbAccessibility(row)"
            >
              ADB开无障碍
            </el-button>
            <el-button
              size="small"
              type="danger"
              plain
              :disabled="!row.online"
              @click="handleDisconnect(row)"
            >
              踢下线
            </el-button>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty description="暂无设备" :image-size="80" />
        </template>
      </el-table>
    </el-card>

    <!-- 位置历史弹窗：上轨迹地图 + 下历史列表 -->
    <LocationDialog
      v-model:visible="locationVisible"
      :device-id="locationDevice?.device_id || ''"
      :device-name="locationDevice?.device_name || ''"
    />

    <!-- ADB 一键开启无障碍弹窗：WiFi 自动连接 / 已连接直接执行 -->
    <el-dialog
      v-model="adbVisible"
      :title="`ADB 开启无障碍 — ${adbDevice?.device_name || ''}`"
      width="560px"
    >
      <el-alert type="info" :closable="false" show-icon style="margin-bottom: 14px">
        <p style="margin: 0">
          经服务端 adb 一次性授权并自动开启「Bridge 辅助服务」，
          <b>成功一次永久有效</b>（重启不掉，仅卸载重装才需重做）。
        </p>
      </el-alert>

      <!-- IP 对照确认：设备上报 IP vs adb 已连设备 IP -->
      <div v-if="adbNetInfo" class="adb-netinfo">
        <div class="adb-netinfo-title">
          设备确认
          <el-tag
            v-if="adbIpMatched !== null"
            :type="adbIpMatched ? 'success' : 'warning'"
            size="small"
            style="margin-left: 6px"
          >
            {{ adbIpMatched ? '✓ IP 已对上，可放心执行' : 'IP 未对上（手机可能走公网连接）' }}
          </el-tag>
        </div>
        <div class="adb-netinfo-row">
          <span class="adb-netinfo-label">本设备上报 IP：</span>
          <span>{{ adbNetInfo.device_ips.length ? adbNetInfo.device_ips.join('、') : '未知' }}</span>
        </div>
        <div class="adb-netinfo-row">
          <span class="adb-netinfo-label">adb 已连设备：</span>
          <span v-if="adbNetInfo.adb_devices.length">
            <span v-for="(d, i) in adbNetInfo.adb_devices" :key="d.serial">
              {{ i > 0 ? '；' : '' }}{{ d.serial }}<template v-if="d.ip">（{{ d.ip }}）</template>
            </span>
          </span>
          <span v-else class="adb-hint">未检测到（未装 adb / 无设备连接——数据线或 WiFi adb 任一即可）</span>
        </div>
      </div>

      <el-radio-group v-model="adbMode" style="margin-bottom: 14px">
        <el-radio-button value="wifi">WiFi 无线调试（推荐）</el-radio-button>
        <el-radio-button value="direct">已用数据线连接</el-radio-button>
      </el-radio-group>

      <template v-if="adbMode === 'wifi'">
        <el-alert type="warning" :closable="false" style="margin-bottom: 12px">
          <p style="margin: 0">
            自动发现失败时用这条路兜底：手机开「无线调试」后填主界面显示的端口
            （首次配对再点「使用配对码配对设备」填配对端口+配对码）。
          </p>
        </el-alert>
        <el-form label-width="140px">
          <el-form-item label="手机 IP">
            <el-input
              v-model="adbIp"
              placeholder="留空自动识别；识别不准时手填（手机 WLAN 设置里查看，如 192.168.1.8）"
              clearable
            />
            <div class="adb-hint">
              手机连服务端走公网时自动识别的 IP 不是局域网 IP，必须手填
            </div>
          </el-form-item>
          <el-form-item label="连接端口">
            <el-input-number
              v-model="adbConnectPort"
              :min="1024"
              :max="65535"
              placeholder="如 43567"
              controls-position="right"
              style="width: 200px"
            />
            <div class="adb-hint">「无线调试」主界面显示的端口号</div>
          </el-form-item>
          <el-form-item label="首次配对">
            <el-switch v-model="adbNeedPair" />
            <div class="adb-hint">之前没配对过这台手机时打开</div>
          </el-form-item>
          <template v-if="adbNeedPair">
            <el-form-item label="配对端口">
              <el-input-number
                v-model="adbPairPort"
                :min="1024"
                :max="65535"
                placeholder="如 37125"
                controls-position="right"
                style="width: 200px"
              />
              <div class="adb-hint">「使用配对码配对设备」页显示的端口</div>
            </el-form-item>
            <el-form-item label="配对码">
              <el-input
                v-model="adbPairCode"
                placeholder="6 位数字"
                maxlength="6"
                style="width: 200px"
              />
            </el-form-item>
          </template>
        </el-form>
      </template>
      <template v-else>
        <el-alert type="success" :closable="false" style="margin-bottom: 12px">
          <p style="margin: 0">
            adb 已有连着的设备（数据线插入或自动发现的无线设备都算），<b>无需填写任何内容</b>，
            只连了一台时直接点下方「连接并开启」即可。
          </p>
        </el-alert>
        <el-form label-width="140px">
          <el-form-item label="手机 IP（可选）">
            <el-input
              v-model="adbIp"
              placeholder="仅连接多台手机需要区分时填写"
              clearable
            />
          </el-form-item>
        </el-form>
      </template>

      <!-- 执行结果（逐步骤，限高滚动） -->
      <template v-if="adbResult">
        <el-divider content-position="left">执行结果</el-divider>
        <div class="adb-result">
          <el-result
            v-if="adbResult.success"
            icon="success"
            title="全部成功"
            sub-title="无障碍已开启，锁屏/静默截屏即刻可用"
          />
          <div v-for="(s, i) in adbResult.steps || []" :key="i" class="adb-step">
            {{ s.ok ? '✅' : '❌' }} <b>{{ s.step }}</b>
            <div v-if="s.detail" class="adb-step-detail">{{ s.detail }}</div>
          </div>
          <el-alert
            v-if="!adbResult.success && adbResult.error"
            type="error"
            :closable="false"
            :title="adbResult.error"
            style="margin-top: 8px"
          />
          <div
            v-if="!adbResult.success && adbResult.adb_devices && adbResult.adb_devices.length"
            class="adb-step-detail"
            style="margin-top: 8px"
          >
            当前 adb 已连设备：
            <span v-for="(d, i) in adbResult.adb_devices" :key="d.serial">
              {{ i > 0 ? '；' : '' }}{{ d.serial }}<template v-if="d.ip">（{{ d.ip }}）</template>
            </span>
          </div>
        </div>
      </template>

      <template #footer>
        <el-button @click="adbVisible = false">关闭</el-button>
        <el-button type="success" :loading="adbRunning" @click="runAdb">
          {{ adbRunning ? '执行中…' : '连接并开启' }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Refresh, Search, Monitor, Connection, CircleClose } from '@element-plus/icons-vue'
import {
  listDevices,
  disconnectDevice,
  adbEnableAccessibility,
  adbWifiEnableAccessibility,
  getDeviceNetInfo,
} from '@/api/devices'
import type { AdbEnableAccessibilityResult, DeviceNetInfo } from '@/api/devices'
import DeviceStatusTag from '@/components/DeviceStatusTag.vue'
import LocationDialog from '@/components/LocationDialog.vue'
import { formatTime } from '@/utils/format'
import type { Device } from '@/types'

const router = useRouter()
const devices = ref<Device[]>([])
const keyword = ref('')
const loading = ref(false)

const locationVisible = ref(false)
const locationDevice = ref<Device | null>(null)

function showLocation(device: Device) {
  locationDevice.value = device
  locationVisible.value = true
}

const filteredDevices = computed(() => {
  const k = keyword.value.trim().toLowerCase()
  if (!k) return devices.value
  return devices.value.filter(
    (d) =>
      d.device_name.toLowerCase().includes(k) ||
      d.device_id.toLowerCase().includes(k),
  )
})

const onlineCount = computed(() => devices.value.filter((d) => d.online).length)
const offlineCount = computed(() => devices.value.filter((d) => !d.online).length)

async function load() {
  loading.value = true
  try {
    devices.value = await listDevices()
  } finally {
    loading.value = false
  }
}

function goCommand(device: Device) {
  router.push({ path: '/command', query: { deviceId: device.device_id } })
}

function goLog(device: Device) {
  router.push({ path: '/log', query: { deviceId: device.device_id } })
}

async function handleDisconnect(device: Device) {
  try {
    await ElMessageBox.confirm(
      `确定要踢下线设备「${device.device_name}」吗？`,
      '提示',
      { type: 'warning' },
    )
  } catch {
    return
  }
  await disconnectDevice(device.device_id)
  ElMessage.success('已踢下线')
  load()
}

/**
 * ADB 一键开启无障碍（借鉴 autox 的 WRITE_SECURE_SETTINGS 方案）：
 * 打开弹窗，支持两种模式——WiFi（填 IP/端口/配对码，服务端自动 pair+connect+
 * 授权+开启）与直接执行（已有 USB/已 connect 的设备）。
 */
const adbVisible = ref(false)
const adbDevice = ref<Device | null>(null)
const adbMode = ref<'wifi' | 'direct'>('wifi')
const adbIp = ref('')
const adbConnectPort = ref<number | undefined>()
const adbPairPort = ref<number | undefined>()
const adbPairCode = ref('')
const adbNeedPair = ref(false)
const adbRunning = ref(false)
const adbResult = ref<AdbEnableAccessibilityResult | null>(null)
const adbNetInfo = ref<DeviceNetInfo | null>(null)

/** 该设备 IP 与 adb 设备 IP 是否有交集（true=放心执行）。 */
const adbIpMatched = computed(() => {
  const info = adbNetInfo.value
  if (!info) return null
  const deviceIps = new Set(info.device_ips)
  return info.adb_devices.some((d) => d.ip && deviceIps.has(d.ip))
})

async function loadNetInfo(deviceId: string) {
  adbNetInfo.value = null
  try {
    adbNetInfo.value = await getDeviceNetInfo(deviceId)
    // adb 已有连着的设备（USB 线 / mDNS 自动发现的无线设备都算）→ 自动切到
    // 「直接执行」模式：用户什么都不用填，选 WiFi + 填端口只是自动发现失败时的兜底
    if (adbNetInfo.value.adb_devices.length > 0) {
      adbMode.value = 'direct'
    }
  } catch {
    /* 拉取失败不打断流程，弹窗仍可用 */
  }
}

function handleAdbAccessibility(device: Device) {
  adbDevice.value = device
  adbMode.value = 'wifi'
  adbIp.value = ''
  adbConnectPort.value = undefined
  adbPairPort.value = undefined
  adbPairCode.value = ''
  adbNeedPair.value = false
  adbResult.value = null
  adbVisible.value = true
  loadNetInfo(device.device_id)
}

async function runAdb() {
  const device = adbDevice.value
  if (!device) return
  if (adbMode.value === 'wifi') {
    const port = adbConnectPort.value
    if (!port || port < 1024 || port > 65535) {
      ElMessage.warning('请填写手机「无线调试」主界面显示的端口号')
      return
    }
    if (adbNeedPair.value && (!adbPairPort.value || !adbPairCode.value.trim())) {
      ElMessage.warning('首次配对需填写配对端口和配对码（手机「使用配对码配对设备」页）')
      return
    }
  }
  adbRunning.value = true
  adbResult.value = null
  let res: AdbEnableAccessibilityResult
  try {
    if (adbMode.value === 'wifi') {
      res = await adbWifiEnableAccessibility(device.device_id, {
        connect_port: adbConnectPort.value!,
        pair_port: adbNeedPair.value ? adbPairPort.value : undefined,
        pair_code: adbNeedPair.value ? adbPairCode.value.trim() : undefined,
        ip: adbIp.value.trim() || undefined,
      })
    } else {
      res = await adbEnableAccessibility(device.device_id, adbIp.value.trim() || undefined)
    }
  } catch (e) {
    adbRunning.value = false
    ElMessage.error(`请求失败：${e instanceof Error ? e.message : String(e)}`)
    return
  }
  adbRunning.value = false
  adbResult.value = res
  if (res.success) {
    ElMessage.success(
      '已开启：「Bridge 辅助服务」已自动启用（锁屏/静默截屏即刻可用，授权永久有效）',
    )
  }
}

onMounted(load)
</script>

<style scoped>
.stats-row {
  margin-bottom: 16px;
}
.stat {
  display: flex;
  align-items: center;
  gap: 14px;
}
.stat-icon {
  width: 48px;
  height: 48px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 24px;
}
.stat-label {
  font-size: 13px;
  color: #909399;
  margin-top: 2px;
}
.adb-hint {
  font-size: 12px;
  color: #909399;
  line-height: 1.4;
  margin-top: 2px;
}
.adb-result {
  max-height: 300px;
  overflow-y: auto;
}
.adb-step {
  font-size: 13px;
  line-height: 1.8;
}
.adb-netinfo {
  border: 1px solid #ebeef5;
  border-radius: 6px;
  padding: 10px 14px;
  margin-bottom: 14px;
  font-size: 13px;
  background: #fafafa;
}
.adb-netinfo-title {
  font-weight: 600;
  margin-bottom: 6px;
}
.adb-netinfo-row {
  line-height: 1.9;
  word-break: break-all;
}
.adb-netinfo-label {
  color: #909399;
}
.adb-step-detail {
  color: #909399;
  word-break: break-all;
  white-space: pre-wrap;
  padding-left: 22px;
}
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}
.search {
  width: 260px;
}
.device-name {
  font-weight: 600;
  color: #303133;
}
</style>
