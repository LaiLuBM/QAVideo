<script setup lang="ts">
import { computed } from 'vue'
import { VIDEO_STATUS_LABELS } from '@/utils/format'

const props = defineProps<{
  status?: string | null
  /** 'video' renders the library state, 'task' the parse-job state. */
  kind?: 'video' | 'task'
}>()

const label = computed(() => {
  const status = props.status ?? ''
  if (props.kind === 'task') {
    return (
      {
        PENDING: '排队中',
        RUNNING: '解析中',
        SUCCESS: '已完成',
        FAILED: '失败',
      }[status] ?? status
    )
  }
  return VIDEO_STATUS_LABELS[status] ?? status ?? '未知'
})

const tone = computed(() => {
  const status = props.status ?? ''
  if (status === 'PARSED' || status === 'SUCCESS') return 'pill--success'
  if (status === 'PARSING' || status === 'RUNNING') return 'pill--info'
  if (status === 'FAILED') return 'pill--danger'
  if (status === 'PENDING' || status === 'IMPORTED') return 'pill--warning'
  return ''
})
</script>

<template>
  <span class="pill" :class="tone">
    <span class="pill__dot" :class="{ 'pill__dot--pulse': status === 'PARSING' || status === 'RUNNING' }" />
    {{ label }}
  </span>
</template>

<style scoped>
.pill__dot--pulse {
  animation: pulse 1.2s ease-in-out infinite;
}

@keyframes pulse {
  0%,
  100% {
    opacity: 1;
    transform: scale(1);
  }
  50% {
    opacity: 0.35;
    transform: scale(0.7);
  }
}
</style>
