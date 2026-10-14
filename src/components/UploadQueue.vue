<script setup lang="ts">
import { computed } from 'vue'
import type { UploadTask } from '@/composables/useUploader'
import { formatBytes } from '@/utils/format'

const props = defineProps<{
  tasks: UploadTask[]
  running: number
}>()

const emit = defineEmits<{
  (event: 'pause', task: UploadTask): void
  (event: 'resume', task: UploadTask): void
  (event: 'cancel', task: UploadTask): void
  (event: 'clear'): void
}>()

const activeCount = computed(
  () => props.tasks.filter((task) => task.status === 'uploading' || task.status === 'queued').length,
)

const hasFinished = computed(() =>
  props.tasks.some((task) => task.status === 'completed' || task.status === 'duplicated'),
)

function statusText(task: UploadTask): string {
  switch (task.status) {
    case 'queued':
      return '等待中'
    case 'uploading':
      return `上传中 ${task.uploadedChunks}/${task.totalChunks} 片`
    case 'paused':
      return `已暂停 ${task.uploadedChunks}/${task.totalChunks} 片 · 可续传`
    case 'completed':
      return '上传完成，已开始解析'
    case 'duplicated':
      return '已存在相同文件，直接复用'
    default:
      return task.error ?? '上传失败'
  }
}

function statusClass(task: UploadTask): string {
  if (task.status === 'failed') return 'upload-row--failed'
  if (task.status === 'completed' || task.status === 'duplicated') return 'upload-row--done'
  if (task.status === 'paused') return 'upload-row--paused'
  return ''
}
</script>

<template>
  <section v-if="props.tasks.length > 0" class="card">
    <header class="card__head">
      <div class="card__title">
        上传队列
        <span class="pill pill--info">{{ activeCount }} 进行中</span>
      </div>
      <button v-if="hasFinished" type="button" class="btn btn--sm btn--ghost" @click="emit('clear')">
        清除已完成
      </button>
    </header>

    <ul class="upload-list">
      <li v-for="task in props.tasks" :key="task.key" class="upload-row" :class="statusClass(task)">
        <div class="upload-row__head">
          <span class="upload-row__name" :title="task.fileName">{{ task.fileName }}</span>
          <span class="upload-row__meta">{{ formatBytes(task.fileSize) }}</span>
        </div>

        <div class="progress">
          <div class="progress__bar" :style="{ width: `${task.progress}%` }" />
        </div>

        <div class="upload-row__foot">
          <span class="upload-row__status" :title="task.error">{{ statusText(task) }}</span>
          <span class="upload-row__actions">
            <span v-if="task.status === 'uploading'" class="upload-row__speed">
              {{ task.bytesPerSecond > 0 ? `${formatBytes(task.bytesPerSecond)}/s` : '' }}
            </span>
            <button
              v-if="task.status === 'uploading' || task.status === 'queued'"
              type="button"
              class="btn btn--sm"
              @click="emit('pause', task)"
            >
              暂停
            </button>
            <button
              v-if="task.status === 'paused' || task.status === 'failed'"
              type="button"
              class="btn btn--sm"
              @click="emit('resume', task)"
            >
              续传
            </button>
            <button
              v-if="task.status !== 'completed' && task.status !== 'duplicated'"
              type="button"
              class="btn btn--sm btn--danger"
              @click="emit('cancel', task)"
            >
              取消
            </button>
          </span>
        </div>
      </li>
    </ul>
  </section>
</template>

<style scoped>
.upload-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.upload-row {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 11px 13px;
  background: rgba(255, 255, 255, 0.02);
}

.upload-row--done {
  border-color: rgba(52, 211, 153, 0.32);
  background: rgba(52, 211, 153, 0.07);
}

.upload-row--failed {
  border-color: rgba(248, 113, 113, 0.32);
  background: rgba(248, 113, 113, 0.07);
}

.upload-row--paused {
  border-color: rgba(251, 191, 36, 0.3);
}

.upload-row__head,
.upload-row__foot {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.upload-row__head {
  margin-bottom: 7px;
}

.upload-row__foot {
  margin-top: 7px;
}

.upload-row__name {
  font-size: 13px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.upload-row__meta,
.upload-row__speed {
  font-size: 11.5px;
  color: var(--text-faint);
}

.upload-row__status {
  font-size: 12px;
  color: var(--text-dim);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.upload-row__actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}
</style>
