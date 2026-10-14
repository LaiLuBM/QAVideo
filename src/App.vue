<script setup lang="ts">
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import AppHeader from '@/components/AppHeader.vue'
import ImportPanel from '@/components/ImportPanel.vue'
import UploadQueue from '@/components/UploadQueue.vue'
import VideoLibrary from '@/components/VideoLibrary.vue'
import VideoDetail from '@/components/VideoDetail.vue'
import { api } from '@/api/video'
import type { VideoImportResponse, VideoStatus } from '@/api/types'
import { useUploader, type UploadTask } from '@/composables/useUploader'
import { useVideoLibrary } from '@/composables/useVideoLibrary'

const {
  videos,
  total,
  totalPages,
  page,
  selectedId,
  keyword,
  statusFilter,
  loading,
  error,
  detail,
  detailLoading,
  systemStatus,
  hasActiveWork: libraryHasActiveWork,
  load,
  loadStatus,
  noteImported,
  remove,
  reparse,
  select,
  closeDetail,
  setPage,
  stopPolling,
} = useVideoLibrary()

const { tasks, running, add, pause, resume, cancel, clearFinished } = useUploader(onImported)

const dragging = ref(false)
const notice = ref<{ tone: 'info' | 'error'; text: string } | null>(null)

/** dragenter/dragleave fire for every child element, so track depth instead of a flag. */
let dragDepth = 0

function onImported(result: VideoImportResponse): void {
  notice.value = result.duplicated
    ? {
        tone: 'info',
        text: `「${result.video.title ?? result.video.id}」已经在库中，直接复用已有记录与解析结果。`,
      }
    : { tone: 'info', text: `「${result.video.title ?? result.video.id}」导入成功，解析任务已排队。` }
  void noteImported(result.video.id)
  void loadStatus()
}

async function importUrl(url: string, title?: string): Promise<void> {
  try {
    const result = await api.importUrl(url, title)
    onImported(result)
  } catch (err) {
    notice.value = { tone: 'error', text: err instanceof Error ? err.message : String(err) }
  }
}

async function removeVideo(id: number): Promise<void> {
  try {
    await remove(id)
    await loadStatus()
    notice.value = { tone: 'info', text: '已删除该视频及其本地文件。' }
  } catch (err) {
    notice.value = { tone: 'error', text: err instanceof Error ? err.message : String(err) }
  }
}

async function reparseVideo(id: number): Promise<void> {
  try {
    await reparse(id)
    notice.value = { tone: 'info', text: '已重新排队解析，进度会自动刷新。' }
  } catch (err) {
    notice.value = { tone: 'error', text: err instanceof Error ? err.message : String(err) }
  }
}

function isDragWithPayload(event: DragEvent): boolean {
  const types = Array.from(event.dataTransfer?.types ?? [])
  return types.includes('Files') || types.includes('text/uri-list') || types.includes('text/plain')
}

function handleDragEnter(event: DragEvent): void {
  if (!isDragWithPayload(event)) return
  event.preventDefault()
  dragDepth += 1
  dragging.value = true
}

function handleDragOver(event: DragEvent): void {
  if (!isDragWithPayload(event)) return
  event.preventDefault()
  if (event.dataTransfer) {
    event.dataTransfer.dropEffect = 'copy'
  }
}

function handleDragLeave(event: DragEvent): void {
  event.preventDefault()
  dragDepth = Math.max(0, dragDepth - 1)
  if (dragDepth === 0) {
    dragging.value = false
  }
}

function handleDrop(event: DragEvent): void {
  if (!isDragWithPayload(event)) return
  event.preventDefault()
  dragDepth = 0
  dragging.value = false

  const dataTransfer = event.dataTransfer
  if (!dataTransfer) return

  const files = Array.from(dataTransfer.files ?? [])
  if (files.length > 0) {
    add(files)
    notice.value = {
      tone: 'info',
      text: `已加入上传队列：${files.length} 个文件（分片上传，支持断点续传）。`,
    }
    return
  }

  const text = (dataTransfer.getData('text/uri-list') || dataTransfer.getData('text/plain')).trim()
  if (/^https?:\/\//i.test(text)) {
    void importUrl(text)
  }
}

function onFiles(files: File[]): void {
  add(files)
}

function onStatusFilter(value: VideoStatus | ''): void {
  statusFilter.value = value
  page.value = 0
  void load()
}

function onKeyword(value: string): void {
  keyword.value = value
  page.value = 0
  void load()
}

function onPause(task: UploadTask): void {
  pause(task)
}

function onResume(task: UploadTask): void {
  resume(task)
  notice.value = { tone: 'info', text: '已从服务端记录的断点继续上传。' }
}

function onCancel(task: UploadTask): void {
  void cancel(task)
}

const hasActiveWork = computed(() => libraryHasActiveWork.value || running.value > 0)

onMounted(() => {
  window.addEventListener('dragenter', handleDragEnter)
  window.addEventListener('dragover', handleDragOver)
  window.addEventListener('dragleave', handleDragLeave)
  window.addEventListener('drop', handleDrop)
  void load()
  void loadStatus()
})

onBeforeUnmount(() => {
  window.removeEventListener('dragenter', handleDragEnter)
  window.removeEventListener('dragover', handleDragOver)
  window.removeEventListener('dragleave', handleDragLeave)
  window.removeEventListener('drop', handleDrop)
  stopPolling()
})
</script>

<template>
  <div class="app-shell">
    <AppHeader :status="systemStatus" :has-active-work="hasActiveWork" />

    <div
      v-if="notice"
      class="alert notice"
      :class="notice.tone === 'error' ? 'alert--error' : 'alert--info'"
      @click="notice = null"
    >
      {{ notice.text }}
      <span class="notice__close">×</span>
    </div>

    <div v-if="error" class="alert alert--error notice">{{ error }}</div>

    <main class="layout">
      <div class="column">
        <ImportPanel :dragging="dragging" @files="onFiles" @url="importUrl" />
        <UploadQueue
          :tasks="tasks"
          :running="running"
          @pause="onPause"
          @resume="onResume"
          @cancel="onCancel"
          @clear="clearFinished"
        />
        <VideoLibrary
          :videos="videos"
          :total="total"
          :page="page"
          :total-pages="totalPages"
          :loading="loading"
          :selected-id="selectedId"
          :keyword="keyword"
          :status-filter="statusFilter"
          @select="select"
          @refresh="load"
          @page="setPage"
          @keyword="onKeyword"
          @status="onStatusFilter"
        />
      </div>

      <div class="column">
        <VideoDetail
          :video="detail"
          :loading="detailLoading"
          @reparse="reparseVideo"
          @remove="removeVideo"
          @close="closeDetail"
        />
      </div>
    </main>

    <!-- whole-window drop target -->
    <div v-if="dragging" class="drop-overlay">
      <div class="drop-overlay__box">
        <strong>松开鼠标，导入到 QAVideo</strong>
        <span>视频文件将分片上传（可断点续传）；拖入链接则直接解析</span>
      </div>
    </div>
  </div>
</template>

<style scoped>
.notice {
  margin-top: 14px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.notice__close {
  font-size: 16px;
  opacity: 0.7;
}

.drop-overlay {
  position: fixed;
  inset: 0;
  z-index: 50;
  display: grid;
  place-items: center;
  background: rgba(6, 10, 24, 0.74);
  backdrop-filter: blur(4px);
  pointer-events: none;
}

.drop-overlay__box {
  border: 2px dashed var(--border-strong);
  border-radius: 22px;
  padding: 40px 56px;
  text-align: center;
  background: rgba(18, 26, 51, 0.9);
  box-shadow: var(--shadow);
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.drop-overlay__box strong {
  font-size: 18px;
}

.drop-overlay__box span {
  font-size: 13px;
  color: var(--text-faint);
}
</style>
