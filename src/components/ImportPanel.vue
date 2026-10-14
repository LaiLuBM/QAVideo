<script setup lang="ts">
import { ref } from 'vue'
import DropZone from './DropZone.vue'

const props = defineProps<{
  dragging: boolean
  disabled?: boolean
}>()

const emit = defineEmits<{
  (event: 'files', files: File[]): void
  (event: 'url', url: string, title?: string): void
}>()

const url = ref('')
const title = ref('')

function submitUrl(): void {
  const value = url.value.trim()
  if (!value) {
    return
  }
  emit('url', value, title.value.trim() || undefined)
  url.value = ''
  title.value = ''
}

function onFiles(files: File[]): void {
  emit('files', files)
}
</script>

<template>
  <section class="card">
    <header class="card__head">
      <div class="card__title">导入视频</div>
      <span class="card__hint">链接导入 · 本地拖拽上传</span>
    </header>

    <div class="url-row">
      <input
        v-model="url"
        class="input"
        type="url"
        placeholder="粘贴视频链接，例如 https://www.bilibili.com/video/BV1xx411c7mD"
        :disabled="props.disabled"
        @keyup.enter="submitUrl"
      />
      <button type="button" class="btn btn--primary" :disabled="props.disabled || !url.trim()" @click="submitUrl">
        解析链接
      </button>
    </div>

    <input
      v-model="title"
      class="input input--title"
      type="text"
      placeholder="可选：自定义标题"
      :disabled="props.disabled"
    />

    <div class="divider"><span>或</span></div>

    <DropZone :active="props.dragging" @files="onFiles" />

    <p class="hint">
      链接会先做规范化去重（忽略 utm/分享参数），同一视频不会重复入库；直链媒体文件会被下载后转写，
      页面类链接目前只做链接级解析。
    </p>
  </section>
</template>

<style scoped>
.url-row {
  display: flex;
  gap: 10px;
}

.url-row .input {
  flex: 1;
}

.input--title {
  margin-top: 10px;
}

.divider {
  display: flex;
  align-items: center;
  gap: 10px;
  margin: 16px 0;
  color: var(--text-faint);
  font-size: 12px;
}

.divider::before,
.divider::after {
  content: '';
  flex: 1;
  height: 1px;
  background: var(--border);
}

.hint {
  margin: 12px 0 0;
  font-size: 12px;
  color: var(--text-faint);
  line-height: 1.7;
}
</style>
