<script setup lang="ts">
import StatusPill from './StatusPill.vue'
import type { VideoBriefDto, VideoStatus } from '@/api/types'
import { SOURCE_LABELS, formatBytes, formatDateTime, relativeTime } from '@/utils/format'

const props = defineProps<{
  videos: VideoBriefDto[]
  total: number
  page: number
  totalPages: number
  loading: boolean
  selectedId: number | null
  keyword: string
  statusFilter: VideoStatus | ''
}>()

const emit = defineEmits<{
  (event: 'select', id: number): void
  (event: 'refresh'): void
  (event: 'page', page: number): void
  (event: 'keyword', value: string): void
  (event: 'status', value: VideoStatus | ''): void
}>()

const filters: Array<{ value: VideoStatus | ''; label: string }> = [
  { value: '', label: '全部' },
  { value: 'PARSED', label: '已解析' },
  { value: 'PARSING', label: '解析中' },
  { value: 'IMPORTED', label: '待解析' },
  { value: 'FAILED', label: '失败' },
]

function onKeyword(event: Event): void {
  emit('keyword', (event.target as HTMLInputElement).value)
}
</script>

<template>
  <section class="card">
    <header class="card__head">
      <div class="card__title">
        视频库
        <span class="card__hint">共 {{ props.total }} 条</span>
      </div>
      <button type="button" class="btn btn--sm" :disabled="props.loading" @click="emit('refresh')">
        {{ props.loading ? '加载中…' : '刷新' }}
      </button>
    </header>

    <div class="filters">
      <input
        class="input"
        type="search"
        placeholder="搜索标题 / 链接 / 文件名"
        :value="props.keyword"
        @input="onKeyword"
      />
      <div class="chips">
        <button
          v-for="filter in filters"
          :key="filter.label"
          type="button"
          class="chip"
          :class="{ 'chip--on': props.statusFilter === filter.value }"
          @click="emit('status', filter.value)"
        >
          {{ filter.label }}
        </button>
      </div>
    </div>

    <div v-if="props.videos.length === 0" class="empty">
      {{ props.loading ? '正在加载…' : '还没有视频，先导入一个链接或把文件拖入窗口吧。' }}
    </div>

    <ul v-else class="video-list scroll-area">
      <li
        v-for="video in props.videos"
        :key="video.id"
        class="video-item"
        :class="{ 'video-item--active': props.selectedId === video.id }"
        @click="emit('select', video.id)"
      >
        <div class="video-item__top">
          <span class="video-item__title" :title="video.title">{{ video.title || '未命名视频' }}</span>
          <StatusPill :status="video.status ?? 'IMPORTED'" />
        </div>

        <p v-if="video.summaryPreview" class="video-item__preview">{{ video.summaryPreview }}</p>
        <p v-else class="video-item__preview video-item__preview--muted">
          {{ video.sourceUrl || video.fileName || '—' }}
        </p>

        <div class="video-item__meta">
          <span class="pill">{{ SOURCE_LABELS[video.sourceType ?? ''] ?? video.sourceType }}</span>
          <span v-if="video.sourceType === 'LOCAL'" class="video-item__dim">{{ formatBytes(video.fileSize) }}</span>
          <span v-if="video.keywords && video.keywords.length" class="video-item__tags">
            {{ video.keywords.slice(0, 3).join(' · ') }}
          </span>
          <span class="video-item__time" :title="formatDateTime(video.createdAt)">
            {{ relativeTime(video.createdAt) }}
          </span>
        </div>

        <div v-if="video.task && (video.task.status === 'RUNNING' || video.task.status === 'PENDING')" class="progress">
          <div class="progress__bar" :style="{ width: `${video.task.progress ?? 0}%` }" />
        </div>
      </li>
    </ul>

    <footer v-if="props.totalPages > 1" class="pager">
      <button
        type="button"
        class="btn btn--sm"
        :disabled="props.page <= 0"
        @click="emit('page', props.page - 1)"
      >
        上一页
      </button>
      <span class="pager__label">{{ props.page + 1 }} / {{ props.totalPages }}</span>
      <button
        type="button"
        class="btn btn--sm"
        :disabled="props.page + 1 >= props.totalPages"
        @click="emit('page', props.page + 1)"
      >
        下一页
      </button>
    </footer>
  </section>
</template>

<style scoped>
.filters {
  display: flex;
  flex-direction: column;
  gap: 10px;
  margin-bottom: 14px;
}

.chips {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.chip {
  border: 1px solid var(--border);
  background: transparent;
  color: var(--text-dim);
  border-radius: 999px;
  padding: 4px 12px;
  font-size: 12px;
  cursor: pointer;
  transition: all 0.16s ease;
}

.chip--on {
  border-color: var(--border-strong);
  background: var(--primary-soft);
  color: #c7d2fe;
}

.video-list {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 9px;
  max-height: 520px;
}

.video-item {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 12px 13px;
  background: rgba(255, 255, 255, 0.02);
  cursor: pointer;
  transition: all 0.16s ease;
}

.video-item:hover {
  border-color: var(--border-strong);
  transform: translateX(2px);
}

.video-item--active {
  border-color: var(--border-strong);
  background: var(--primary-soft);
}

.video-item__top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.video-item__title {
  font-size: 13.5px;
  font-weight: 600;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.video-item__preview {
  margin: 6px 0 8px;
  font-size: 12.5px;
  color: var(--text-dim);
  display: -webkit-box;
  -webkit-line-clamp: 2;
  line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
}

.video-item__preview--muted {
  color: var(--text-faint);
  word-break: break-all;
}

.video-item__meta {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11.5px;
  color: var(--text-faint);
  flex-wrap: wrap;
}

.video-item__tags {
  color: #93c5fd;
}

.video-item__time {
  margin-left: auto;
}

.progress {
  margin-top: 9px;
}

.pager {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
  margin-top: 14px;
}

.pager__label {
  font-size: 12px;
  color: var(--text-faint);
}
</style>
