<script setup lang="ts">
import { ref } from 'vue'

defineProps<{
  /** true while a file is being dragged anywhere over the window. */
  active: boolean
}>()

const emit = defineEmits<{
  (event: 'files', files: File[]): void
}>()

const fileInput = ref<HTMLInputElement | null>(null)

function pick(): void {
  fileInput.value?.click()
}

function onPicked(event: Event): void {
  const input = event.target as HTMLInputElement
  const files = Array.from(input.files ?? [])
  if (files.length > 0) {
    emit('files', files)
  }
  input.value = ''
}
</script>

<template>
  <div class="dropzone" :class="{ 'dropzone--active': active }">
    <div class="dropzone__icon" aria-hidden="true">
      <svg viewBox="0 0 24 24" width="30" height="30" fill="none" stroke="currentColor" stroke-width="1.6">
        <path d="M12 16V4m0 0L7.5 8.5M12 4l4.5 4.5" stroke-linecap="round" stroke-linejoin="round" />
        <path d="M4 15v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3" stroke-linecap="round" />
      </svg>
    </div>
    <div class="dropzone__text">
      <strong>{{ active ? '松开鼠标即可上传' : '把视频文件拖入窗口' }}</strong>
      <span>支持 mp4 / webm / mov / mkv / mp3，单文件最大 2&nbsp;GB，分片上传可断点续传</span>
    </div>
    <button type="button" class="btn" @click="pick">选择文件</button>
    <input
      ref="fileInput"
      class="dropzone__input"
      type="file"
      multiple
      accept="video/*,audio/*"
      @change="onPicked"
    />
  </div>
</template>

<style scoped>
.dropzone {
  display: flex;
  align-items: center;
  gap: 14px;
  border: 1.5px dashed var(--border);
  border-radius: var(--radius);
  padding: 16px 18px;
  background: rgba(255, 255, 255, 0.02);
  transition: all 0.18s ease;
}

.dropzone--active {
  border-color: var(--border-strong);
  background: var(--primary-soft);
  transform: translateY(-1px);
}

.dropzone__icon {
  display: grid;
  place-items: center;
  width: 48px;
  height: 48px;
  border-radius: 14px;
  background: var(--primary-soft);
  color: #a5b4fc;
  flex-shrink: 0;
}

.dropzone__text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  flex: 1;
  min-width: 0;
}

.dropzone__text strong {
  font-size: 14px;
}

.dropzone__text span {
  font-size: 12px;
  color: var(--text-faint);
}

.dropzone__input {
  display: none;
}
</style>
