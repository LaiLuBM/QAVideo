<script setup lang="ts">
import { computed } from 'vue'
import type { SystemStatusDto } from '@/api/types'

const props = defineProps<{
  status: SystemStatusDto | null
  hasActiveWork: boolean
}>()

const title = computed(() => import.meta.env.VITE_APP_TITLE ?? 'QAVideo')

const aiLabel = computed(() => {
  const status = props.status
  if (!status) return 'AI 状态未知'
  if (status.aiApiKeyConfigured) {
    return `${status.aiProvider ?? 'deepseek'} · ${status.aiModel ?? ''}`
  }
  return '本地兜底摘要（未配置密钥）'
})

const aiTone = computed(() => (props.status?.aiApiKeyConfigured ? 'pill--success' : 'pill--warning'))
const mqTone = computed(() => (props.status?.rocketMqEnabled ? 'pill--success' : 'pill--warning'))
const redisTone = computed(() =>
  props.status?.redisReachable ? 'pill--success' : props.status?.redisEnabled ? 'pill--danger' : 'pill--warning',
)
</script>

<template>
  <header class="header">
    <div class="brand">
      <div class="brand__mark" aria-hidden="true">QA</div>
      <div>
        <h1 class="brand__title">{{ title }}</h1>
        <p class="brand__subtitle">
          AI 视频解析 · 总结平台 — Vue 3 + Spring Boot + MySQL + Redis + RocketMQ + LangChain4j(DeepSeek)
        </p>
      </div>
    </div>

    <div class="status-strip">
      <span
        class="pill"
        :class="aiTone"
        :title="
          props.status?.aiApiKeyConfigured
            ? '已检测到 DEEPSEEK_API_KEY'
            : '后端启动时未读取到 DEEPSEEK_API_KEY；设置环境变量后需要重启后端进程才会生效'
        "
      >
        <span class="pill__dot" />
        AI: {{ aiLabel }}
      </span>
      <span class="pill" :class="mqTone">
        <span class="pill__dot" />
        MQ: {{ props.status?.rocketMqEnabled ? 'RocketMQ 异步' : '本地线程池' }}
      </span>
      <span class="pill" :class="redisTone">
        <span class="pill__dot" />
        Redis: {{ props.status?.redisReachable ? '已连接（支持断点续传）' : props.status?.redisEnabled ? '不可用' : '内存模式' }}
      </span>
      <span class="pill">
        <span v-if="props.hasActiveWork" class="pill__dot pill__dot--live" />
        {{ props.hasActiveWork ? '解析进行中' : '空闲' }}
      </span>
      <span class="pill">视频 {{ props.status?.videoCount ?? 0 }} · 待处理 {{ props.status?.pendingTaskCount ?? 0 }}</span>
    </div>
  </header>
</template>

<style scoped>
.header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 18px;
  flex-wrap: wrap;
}

.brand {
  display: flex;
  align-items: center;
  gap: 14px;
}

.brand__mark {
  width: 46px;
  height: 46px;
  border-radius: 14px;
  display: grid;
  place-items: center;
  font-weight: 700;
  letter-spacing: 0.04em;
  background: linear-gradient(135deg, #6366f1, #22d3ee);
  color: #06122b;
  box-shadow: 0 12px 26px rgba(56, 189, 248, 0.28);
}

.brand__title {
  font-size: 20px;
  margin: 0;
}

.brand__subtitle {
  margin: 2px 0 0;
  font-size: 12.5px;
  color: var(--text-faint);
}

.status-strip {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.pill__dot--live {
  background: var(--accent);
  animation: blink 1.2s ease-in-out infinite;
}

@keyframes blink {
  0%,
  100% {
    opacity: 1;
  }
  50% {
    opacity: 0.25;
  }
}
</style>
