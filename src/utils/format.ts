export function formatBytes(bytes?: number | null): string {
  if (bytes === undefined || bytes === null || bytes <= 0) {
    return '—'
  }
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let value = bytes
  let unit = 0
  while (value >= 1024 && unit < units.length - 1) {
    value /= 1024
    unit += 1
  }
  return `${value >= 100 || unit === 0 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`
}

export function formatDuration(seconds?: number | null): string {
  if (!seconds || seconds <= 0) {
    return '—'
  }
  const h = Math.floor(seconds / 3600)
  const m = Math.floor((seconds % 3600) / 60)
  const s = Math.floor(seconds % 60)
  const mm = String(m).padStart(2, '0')
  const ss = String(s).padStart(2, '0')
  return h > 0 ? `${h}:${mm}:${ss}` : `${m}:${ss}`
}

export function formatDateTime(value?: string | null): string {
  if (!value) {
    return '—'
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return value
  }
  const pad = (n: number) => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())} ${pad(
    date.getHours(),
  )}:${pad(date.getMinutes())}`
}

export function relativeTime(value?: string | null): string {
  if (!value) {
    return ''
  }
  const date = new Date(value)
  if (Number.isNaN(date.getTime())) {
    return ''
  }
  const diff = Date.now() - date.getTime()
  const minute = 60_000
  if (diff < minute) return '刚刚'
  if (diff < 60 * minute) return `${Math.floor(diff / minute)} 分钟前`
  if (diff < 24 * 60 * minute) return `${Math.floor(diff / (60 * minute))} 小时前`
  if (diff < 30 * 24 * 60 * minute) return `${Math.floor(diff / (24 * 60 * minute))} 天前`
  return formatDateTime(value)
}

export function formatMs(ms?: number | null): string {
  if (ms === undefined || ms === null || ms < 0) {
    return '—'
  }
  if (ms < 1000) return `${ms} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)} s`
  return `${Math.floor(ms / 60_000)} 分 ${Math.round((ms % 60_000) / 1000)} 秒`
}

export const STAGE_LABELS: Record<string, string> = {
  QUEUED: '排队中',
  ACQUIRE: '获取视频源',
  TRANSCRIBE: '提取字幕 / 语音转写',
  SUMMARIZE: '大模型解析总结',
  PERSIST: '写入结果',
  DONE: '已完成',
  FAILED: '失败',
}

export const VIDEO_STATUS_LABELS: Record<string, string> = {
  IMPORTED: '待解析',
  PARSING: '解析中',
  PARSED: '已解析',
  FAILED: '解析失败',
}

export const SOURCE_LABELS: Record<string, string> = {
  URL: '链接',
  LOCAL: '本地上传',
}

export function isActiveTask(status?: string | null): boolean {
  return status === 'PENDING' || status === 'RUNNING'
}
