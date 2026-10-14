<script setup lang="ts">
import { computed, ref } from 'vue'
import StatusPill from './StatusPill.vue'
import { mediaUrl } from '@/api/client'
import type { VideoDetailDto } from '@/api/types'
import {
  SOURCE_LABELS,
  STAGE_LABELS,
  formatBytes,
  formatDateTime,
  formatDuration,
  formatMs,
} from '@/utils/format'

const props = defineProps<{
  video: VideoDetailDto | null
  loading: boolean
}>()

const emit = defineEmits<{
  (event: 'reparse', id: number): void
  (event: 'remove', id: number): void
  (event: 'close'): void
}>()

const showTranscript = ref(false)
const copied = ref(false)

const task = computed(() => props.video?.task ?? null)
const isRunning = computed(
  () => task.value?.status === 'RUNNING' || task.value?.status === 'PENDING',
)
const streamUrl = computed(() => mediaUrl(props.video?.playUrl))
const stageLabel = computed(() => {
  const stage = task.value?.stage
  return stage ? (STAGE_LABELS[stage] ?? stage) : '解析中'
})

function confirmRemove(): void {
  if (!props.video) return
  if (window.confirm(`确定删除「${props.video.title ?? props.video.id}」？本地文件也会一并清理。`)) {
    emit('remove', props.video.id)
  }
}

async function copyTranscript(): Promise<void> {
  const text = props.video?.transcript
  if (!text) return
  try {
    await navigator.clipboard.writeText(text)
    copied.value = true
    window.setTimeout(() => {
      copied.value = false
    }, 1600)
  } catch {
    copied.value = false
  }
}
</script>

<template>
  <section class="card detail">
    <div v-if="props.loading" class="empty">正在加载视频详情…</div>

    <div v-else-if="!props.video" class="empty">
      <strong>选择左侧视频查看解析结果</strong>
      <p class="detail__hint">
        导入后解析任务会异步执行：RocketMQ 投递作业 → LangChain4j 调用 DeepSeek →
        总结与章节写回 MySQL。这里会自动刷新进度。
      </p>
    </div>

    <template v-else>
      <header class="detail__head">
        <div class="detail__title-row">
          <h2 class="detail__title">{{ props.video.title || '未命名视频' }}</h2>
          <StatusPill :status="props.video.status ?? 'IMPORTED'" />
        </div>
        <div class="detail__actions">
          <button type="button" class="btn btn--sm" :disabled="isRunning" @click="emit('reparse', props.video.id)">
            重新解析
          </button>
          <button type="button" class="btn btn--sm btn--danger" @click="confirmRemove">删除</button>
          <button type="button" class="btn btn--sm btn--ghost" @click="emit('close')">关闭</button>
        </div>
      </header>

      <div class="detail__meta">
        <span class="pill">{{ SOURCE_LABELS[props.video.sourceType ?? ''] ?? '未知来源' }}</span>
        <span v-if="props.video.fileSize" class="detail__dim">{{ formatBytes(props.video.fileSize) }}</span>
        <span v-if="props.video.durationSeconds" class="detail__dim">
          时长 {{ formatDuration(props.video.durationSeconds) }}
        </span>
        <span class="detail__dim">导入于 {{ formatDateTime(props.video.createdAt) }}</span>
      </div>

      <a
        v-if="props.video.sourceUrl"
        class="detail__source mono"
        :href="props.video.sourceUrl"
        target="_blank"
        rel="noopener noreferrer"
      >
        {{ props.video.sourceUrl }}
      </a>

      <!-- progress ------------------------------------------------------- -->
      <div v-if="isRunning" class="panel panel--info">
        <div class="panel__head">
          <span>{{ stageLabel }}</span>
          <span class="mono">{{ task?.progress ?? 0 }}%</span>
        </div>
        <div class="progress">
          <div class="progress__bar" :style="{ width: `${task?.progress ?? 0}%` }" />
        </div>
        <p class="panel__foot">
          任务 #{{ task?.id }} · 模型 {{ task?.model ?? '待定' }} · 开始于
          {{ formatDateTime(task?.startedAt) }}
        </p>
      </div>

      <!-- failure -------------------------------------------------------- -->
      <div v-if="task?.status === 'FAILED'" class="alert alert--error">
        <strong>解析失败：</strong>{{ task.errorMessage || '未知错误' }}
      </div>

      <!-- mock banner ---------------------------------------------------- -->
      <div v-if="task?.mockAi" class="alert alert--warning">
        当前结果由<strong>本地兜底摘要</strong>生成，不是 DeepSeek 的输出。两种情况：后端启动时没读到
        <code class="mono">DEEPSEEK_API_KEY</code>，或者 DeepSeek 调用失败（失败原因会附在总结末尾）。
        <strong>环境变量是在进程启动时读取的，设置后必须重启后端才生效</strong>；恢复后点「重新解析」即可。
      </div>

      <!-- player --------------------------------------------------------- -->
      <video
        v-if="streamUrl"
        class="player"
        :src="streamUrl"
        controls
        preload="metadata"
      />

      <!-- summary -------------------------------------------------------- -->
      <section v-if="props.video.summary" class="block">
        <h3 class="block__title">AI 总结</h3>
        <div class="summary">{{ props.video.summary }}</div>
      </section>

      <section v-if="props.video.keywords?.length" class="block">
        <h3 class="block__title">关键词</h3>
        <div class="tag-list">
          <span v-for="keyword in props.video.keywords" :key="keyword" class="pill pill--info">{{ keyword }}</span>
        </div>
      </section>

      <section v-if="props.video.highlights?.length" class="block">
        <h3 class="block__title">要点</h3>
        <ul class="bullet-list">
          <li v-for="(item, index) in props.video.highlights" :key="index">{{ item }}</li>
        </ul>
      </section>

      <section v-if="props.video.chapters?.length" class="block">
        <h3 class="block__title">章节</h3>
        <ol class="chapters">
          <li v-for="(chapter, index) in props.video.chapters" :key="index" class="chapter">
            <span class="chapter__time mono">{{ chapter.start || '--:--' }}</span>
            <div class="chapter__body">
              <strong>{{ chapter.title || '未命名章节' }}</strong>
              <p v-if="chapter.summary">{{ chapter.summary }}</p>
            </div>
          </li>
        </ol>
      </section>

      <section v-if="props.video.transcript" class="block">
        <div class="block__head">
          <h3 class="block__title">
            转写文本
            <span class="card__hint">{{ props.video.transcript.length }} 字</span>
          </h3>
          <span class="block__tools">
            <button type="button" class="btn btn--sm btn--ghost" @click="copyTranscript">
              {{ copied ? '已复制' : '复制' }}
            </button>
            <button type="button" class="btn btn--sm btn--ghost" @click="showTranscript = !showTranscript">
              {{ showTranscript ? '收起' : '展开' }}
            </button>
          </span>
        </div>
        <pre v-if="showTranscript" class="transcript scroll-area">{{ props.video.transcript }}</pre>
      </section>

      <section v-if="props.video.recentTasks?.length" class="block">
        <h3 class="block__title">解析历史</h3>
        <ul class="history">
          <li v-for="item in props.video.recentTasks" :key="item.id" class="history__row">
            <span class="mono">#{{ item.id }}</span>
            <StatusPill kind="task" :status="item.status ?? ''" />
            <span class="history__stage">{{ item.stage ? (STAGE_LABELS[item.stage] ?? item.stage) : '—' }}</span>
            <span class="history__dim">{{ formatMs(item.durationMs) }}</span>
            <span class="history__dim">{{ item.model ?? '' }}</span>
            <span class="history__dim">{{ formatDateTime(item.createdAt) }}</span>
          </li>
        </ul>
      </section>

      <p v-if="props.video.contentHash" class="detail__hash mono">sha256: {{ props.video.contentHash }}</p>
    </template>
  </section>
</template>

<style scoped>
.detail {
  position: sticky;
  top: 20px;
}

.detail__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
}

.detail__title-row {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.detail__title {
  font-size: 17px;
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.detail__actions {
  display: flex;
  gap: 6px;
}

.detail__meta {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  margin-top: 10px;
  font-size: 12px;
}

.detail__dim {
  color: var(--text-faint);
}

.detail__source {
  display: block;
  margin-top: 8px;
  word-break: break-all;
  color: var(--accent);
}

.detail__hint {
  margin-top: 8px;
  font-size: 12.5px;
  line-height: 1.8;
}

.detail__hash {
  margin-top: 18px;
  color: var(--text-faint);
  word-break: break-all;
}

.panel {
  border: 1px solid var(--border);
  border-radius: var(--radius);
  padding: 12px 14px;
  margin-top: 14px;
}

.panel--info {
  border-color: rgba(99, 102, 241, 0.32);
  background: rgba(99, 102, 241, 0.09);
}

.panel__head {
  display: flex;
  justify-content: space-between;
  font-size: 13px;
  margin-bottom: 8px;
}

.panel__foot {
  margin: 8px 0 0;
  font-size: 11.5px;
  color: var(--text-faint);
}

.alert {
  margin-top: 14px;
}

.player {
  width: 100%;
  margin-top: 16px;
  border-radius: var(--radius);
  background: #000;
  max-height: 320px;
}

.block {
  margin-top: 20px;
}

.block__head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
}

.block__title {
  font-size: 13.5px;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--text-dim);
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 10px;
}

.block__tools {
  display: flex;
  gap: 6px;
}

.summary {
  white-space: pre-wrap;
  font-size: 14px;
  line-height: 1.85;
  color: #dbe2f7;
}

.bullet-list {
  margin: 0;
  padding-left: 18px;
  line-height: 1.9;
  color: var(--text-dim);
}

.chapters {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 10px;
}

.chapter {
  display: flex;
  gap: 12px;
  border-left: 2px solid var(--border-strong);
  padding: 2px 0 2px 12px;
}

.chapter__time {
  color: var(--accent);
  flex-shrink: 0;
  padding-top: 2px;
}

.chapter__body p {
  margin: 2px 0 0;
  font-size: 12.5px;
  color: var(--text-faint);
}

.transcript {
  margin: 0;
  padding: 13px;
  max-height: 320px;
  border: 1px solid var(--border);
  border-radius: var(--radius);
  background: rgba(8, 12, 26, 0.7);
  font-size: 12.5px;
  line-height: 1.8;
  white-space: pre-wrap;
  word-break: break-word;
  color: var(--text-dim);
}

.history {
  list-style: none;
  margin: 0;
  padding: 0;
  display: flex;
  flex-direction: column;
  gap: 7px;
}

.history__row {
  display: flex;
  align-items: center;
  gap: 10px;
  font-size: 12px;
  border-bottom: 1px dashed var(--border);
  padding-bottom: 6px;
}

.history__stage {
  color: var(--text-dim);
}

.history__dim {
  color: var(--text-faint);
  margin-left: auto;
}

.history__dim + .history__dim {
  margin-left: 0;
}
</style>
