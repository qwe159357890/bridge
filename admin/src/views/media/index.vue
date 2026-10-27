<template>
  <div>
    <el-card>
      <template #header>
        <div class="card-header">
          <span class="title">媒体文件（截屏 / 拍照 / 录屏 / 录像 / 录音）</span>
          <div class="header-right">
            <el-select v-model="kindFilter" clearable placeholder="全部类型" style="width: 120px">
              <el-option label="截屏" value="screenshot" />
              <el-option label="拍照" value="photo" />
              <el-option label="录屏" value="screen_record" />
              <el-option label="录像" value="video" />
              <el-option label="录音" value="audio" />
            </el-select>
            <el-button type="primary" :icon="Refresh" size="small" @click="load">刷新</el-button>
          </div>
        </div>
      </template>

      <el-alert
        class="cleanup-tip"
        type="info"
        :closable="false"
        show-icon
        title="服务端自动清理：默认保留最近 100 条且不超过 7 天（先到先触发），阈值可在 server/config.yaml 调整"
      />

      <el-table :data="filtered" v-loading="loading" stripe>
        <el-table-column label="预览" width="140">
          <template #default="{ row }">
            <el-image
              v-if="isImageKind(row.kind) && row.exists"
              :src="mediaThumbUrl(row.id)"
              :preview-src-list="[mediaFileUrl(row.id)]"
              preview-teleported
              lazy
              fit="cover"
              class="thumb"
            >
              <template #error>
                <div class="thumb thumb-error">无法加载</div>
              </template>
            </el-image>
            <!-- 视频（录屏/录像）封面：直接加载视频首帧（#t=0.1 媒体片段 + preload=metadata，
                 Chrome/Edge 原生支持，零后端），叠播放角标；加载失败回退纯图标 -->
            <div
              v-else-if="(row.kind === 'screen_record' || row.kind === 'video') && row.exists"
              class="video-thumb video-cover"
              @click="playMedia(row)"
            >
              <video
                v-show="!videoThumbError[row.id]"
                class="video-cover-frame"
                :src="mediaFileUrl(row.id) + '#t=0.1'"
                preload="metadata"
                muted
                @error="markVideoThumbError(row.id)"
              />
              <!-- 首帧加载失败：回退纯图标样式 -->
              <template v-if="videoThumbError[row.id]">
                <el-icon><VideoPlay /></el-icon>
                <span>播放</span>
              </template>
              <span class="video-cover-badge"><el-icon><VideoPlay /></el-icon></span>
            </div>
            <div
              v-else-if="row.kind === 'audio' && row.exists"
              class="video-thumb"
              @click="playMedia(row)"
            >
              <el-icon><VideoPlay /></el-icon>
              <span>试听</span>
            </div>
            <!-- 直播存档（FLV）：浏览器原生不支持内联播放，提供下载 -->
            <div
              v-else-if="row.kind === 'screen_live' && row.exists"
              class="video-thumb"
              @click="download(row)"
            >
              <el-icon><Download /></el-icon>
              <span>下载</span>
            </div>
            <div v-else class="thumb thumb-error">文件缺失</div>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="90">
          <template #default="{ row }">
            <el-tag :type="kindTagType(row.kind)" size="small">{{ kindIcon(row.kind) }} {{ row.kind_label }}</el-tag>
          </template>
        </el-table-column>
        <el-table-column label="设备" min-width="130">
          <template #default="{ row }">{{ deviceNameOf(row.device_id) }}</template>
        </el-table-column>
        <el-table-column prop="filename" label="文件名" min-width="220" show-overflow-tooltip />
        <el-table-column label="大小" width="100">
          <template #default="{ row }">{{ formatSize(row.size) }}</template>
        </el-table-column>
        <el-table-column label="上传时间" width="180">
          <template #default="{ row }">{{ row.created_at }}</template>
        </el-table-column>
        <el-table-column label="操作" width="150" fixed="right">
          <template #default="{ row }">
            <el-button
              v-if="isImageKind(row.kind)"
              size="small"
              type="primary"
              link
              @click="previewImage(row)"
            >
              查看
            </el-button>
            <el-button size="small" type="primary" link @click="download(row)">下载</el-button>
            <el-button size="small" type="danger" link @click="remove(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <!-- 图片大图预览 -->
    <el-image-viewer
      v-if="viewerVisible"
      :url-list="viewerUrls"
      teleported
      @close="viewerVisible = false"
    />

    <!-- 视频/音频播放对话框 -->
    <el-dialog v-model="videoVisible" :title="videoTitle" width="720px" destroy-on-close>
      <video
        v-if="videoUrl && !videoIsAudio"
        :src="videoUrl"
        controls
        autoplay
        style="width: 100%; max-height: 60vh; background: #000; border-radius: 6px"
      />
      <audio v-else-if="videoUrl" :src="videoUrl" controls autoplay style="width: 100%" />
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Refresh, VideoPlay } from '@element-plus/icons-vue'
import { listMedia, mediaFileUrl, mediaThumbUrl, deleteMedia } from '@/api/media'
import type { MediaItem } from '@/api/media'
import { listDevices } from '@/api/devices'
import type { Device } from '@/types'

const items = ref<MediaItem[]>([])
const devices = ref<Device[]>([])
const loading = ref(false)
const kindFilter = ref('')

const filtered = computed(() =>
  kindFilter.value ? items.value.filter((i) => i.kind === kindFilter.value) : items.value,
)

// 图片大图预览
const viewerVisible = ref(false)
const viewerUrls = ref<string[]>([])

// 视频播放
const videoVisible = ref(false)
const videoUrl = ref('')
const videoTitle = ref('')
const videoIsAudio = ref(false)

// 视频封面首帧加载失败的记录（media id → true），失败回退纯播放图标
const videoThumbError = ref<Record<number, boolean>>({})
function markVideoThumbError(id: number) {
  videoThumbError.value = { ...videoThumbError.value, [id]: true }
}

function isImageKind(kind: string) {
  return kind === 'screenshot' || kind === 'photo'
}

function previewImage(row: MediaItem) {
  viewerUrls.value = [mediaFileUrl(row.id)]
  viewerVisible.value = true
}

function playMedia(row: MediaItem) {
  videoUrl.value = mediaFileUrl(row.id)
  videoTitle.value = row.filename
  videoIsAudio.value = row.kind === 'audio'
  videoVisible.value = true
}

function download(row: MediaItem) {
  const a = document.createElement('a')
  a.href = mediaFileUrl(row.id)
  a.download = row.filename
  a.click()
}

async function remove(row: MediaItem) {
  try {
    await ElMessageBox.confirm(
      `确定删除「${row.filename}」？记录与磁盘文件将一并删除。`,
      '删除确认',
      { type: 'warning' },
    )
  } catch {
    return
  }
  try {
    await deleteMedia(row.id)
    ElMessage.success('已删除')
    load()
  } catch {
    // 错误已统一提示
  }
}

function kindIcon(kind: string) {
  return kind === 'screenshot'
    ? '📸'
    : kind === 'photo'
      ? '📷'
      : kind === 'screen_record'
        ? '🎥'
        : kind === 'screen_live'
          ? '🖥️'
          : kind === 'video'
            ? '🎬'
            : '🎙️'
}

function kindTagType(kind: string) {
  return kind === 'screenshot'
    ? 'primary'
    : kind === 'photo'
      ? 'success'
      : kind === 'screen_record'
        ? 'warning'
        : kind === 'screen_live'
          ? 'warning'
          : kind === 'video'
            ? 'danger'
            : 'info'
}

function deviceNameOf(id: string) {
  const d = devices.value.find((x) => x.device_id === id)
  return d ? d.device_name : id
}

function formatSize(bytes: number) {
  if (bytes >= 1048576) return `${(bytes / 1048576).toFixed(1)} MB`
  return `${Math.max(1, Math.round(bytes / 1024))} KB`
}

async function load() {
  loading.value = true
  try {
    const r = await listMedia()
    items.value = r.items
  } finally {
    loading.value = false
  }
}

onMounted(async () => {
  load()
  try {
    devices.value = await listDevices()
  } catch {
    // 设备列表失败不阻塞（显示原始 device_id）
  }
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.title {
  font-weight: 600;
}
.header-right {
  display: flex;
  align-items: center;
  gap: 10px;
}
.cleanup-tip {
  margin-bottom: 12px;
}
.thumb {
  width: 110px;
  height: 62px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: #f5f7fa;
  color: #909399;
  font-size: 12px;
}
.thumb-error {
  border: 1px dashed #dcdfe6;
}
.video-thumb {
  width: 110px;
  height: 62px;
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  background: #303133;
  color: #fff;
  font-size: 13px;
  cursor: pointer;
}
.video-thumb:hover {
  background: #409eff;
}
/* 视频首帧封面：video 铺满卡片 + 右下角播放角标 */
.video-cover {
  position: relative;
  overflow: hidden;
  padding: 0;
  gap: 0;
}
.video-cover-frame {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
  background: #303133;
}
.video-cover-badge {
  position: absolute;
  right: 4px;
  bottom: 2px;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.55);
  color: #fff;
  font-size: 13px;
  pointer-events: none;
}
</style>
