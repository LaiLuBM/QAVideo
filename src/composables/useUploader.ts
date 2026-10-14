import { reactive, ref } from 'vue'
import { api } from '@/api/video'
import type { VideoImportResponse } from '@/api/types'

export type UploadStatus =
  | 'queued'
  | 'uploading'
  | 'paused'
  | 'completed'
  | 'failed'
  | 'duplicated'

export interface UploadTask {
  key: string
  file: File
  fileName: string
  fileSize: number
  status: UploadStatus
  progress: number
  uploadedChunks: number
  totalChunks: number
  chunkSize: number
  uploadId?: string
  bytesPerSecond: number
  error?: string
  result?: VideoImportResponse
}

const STORAGE_PREFIX = 'qavideo:upload:'
/** How many files transfer at the same time. Chunks of one file stay sequential so the
 *  resume offset is always easy to reason about. */
const CONCURRENT_FILES = 2
const MAX_CHUNK_RETRIES = 3

function fingerprint(file: File): string {
  return `${file.name}::${file.size}::${file.lastModified}`
}

/**
 * Breakpoint-resume bookkeeping.
 *
 * The server owns the authoritative chunk inventory (Redis); the browser only remembers
 * which session id belongs to which file, so dropping the same file again continues
 * instead of starting from zero.
 */
function readResumeId(file: File): string | undefined {
  try {
    const raw = localStorage.getItem(STORAGE_PREFIX + fingerprint(file))
    if (!raw) return undefined
    const parsed = JSON.parse(raw) as { uploadId?: string }
    return parsed.uploadId
  } catch {
    return undefined
  }
}

function writeResumeId(file: File, uploadId: string): void {
  try {
    localStorage.setItem(
      STORAGE_PREFIX + fingerprint(file),
      JSON.stringify({ uploadId, fileName: file.name, fileSize: file.size, updatedAt: Date.now() }),
    )
  } catch {
    // private mode / quota - resume simply will not survive a reload
  }
}

function clearResumeId(file: File): void {
  try {
    localStorage.removeItem(STORAGE_PREFIX + fingerprint(file))
  } catch {
    // ignore
  }
}

function sleep(ms: number): Promise<void> {
  return new Promise((resolve) => window.setTimeout(resolve, ms))
}

/**
 * Drag-and-drop upload engine with chunked transfer, retry and breakpoint resume.
 */
export function useUploader(onImported: (result: VideoImportResponse) => void) {
  const tasks = reactive<UploadTask[]>([])
  const running = ref(0)

  function add(files: File[]): void {
    files.forEach((file, offset) => {
      tasks.push({
        key: `${Date.now()}-${offset}-${fingerprint(file)}`,
        file,
        fileName: file.name,
        fileSize: file.size,
        status: 'queued',
        progress: 0,
        uploadedChunks: 0,
        totalChunks: 0,
        chunkSize: 0,
        bytesPerSecond: 0,
      })
    })
    pump()
  }

  function pump(): void {
    while (running.value < CONCURRENT_FILES) {
      const next = tasks.find((task) => task.status === 'queued')
      if (!next) {
        return
      }
      void run(next)
    }
  }

  async function run(task: UploadTask): Promise<void> {
    running.value += 1
    task.status = 'uploading'
    task.error = undefined
    try {
      await transfer(task)
    } catch (error) {
      if (!isPaused(task)) {
        task.status = 'failed'
        task.error = error instanceof Error ? error.message : String(error)
      }
    } finally {
      running.value -= 1
      pump()
    }
  }

  /** A user pause unwinds the transfer by throwing; it is not a failure. */
  function isPaused(task: UploadTask): boolean {
    return task.status === 'paused'
  }

  async function transfer(task: UploadTask): Promise<void> {
    const file = task.file
    const resumeId = task.uploadId ?? readResumeId(file)

    const init = await api.initUpload({
      fileName: file.name,
      fileSize: file.size,
      contentType: file.type || 'application/octet-stream',
      uploadId: resumeId,
      autoParse: true,
    })

    // The server already stores identical bytes: nothing to transfer.
    if (init.duplicate) {
      clearResumeId(file)
      task.uploadId = undefined
      task.progress = 100
      task.status = 'duplicated'
      task.result = init.duplicate
      onImported(init.duplicate)
      return
    }

    if (!init.uploadId) {
      throw new Error('服务端未返回上传会话标识')
    }

    const uploadId = init.uploadId
    task.uploadId = uploadId
    writeResumeId(file, uploadId)
    task.chunkSize = init.chunkSize ?? 0
    task.totalChunks = init.totalChunks ?? 0
    if (task.chunkSize <= 0 || task.totalChunks <= 0) {
      throw new Error('服务端返回的分片信息非法')
    }

    const done = new Set<number>(init.uploadedChunks ?? [])
    task.uploadedChunks = done.size
    updateProgress(task)
    if (done.size > 0) {
      // resumed: bytes already on the server count as transferred from here on
      task.progress = Math.max(task.progress, Math.floor((done.size / task.totalChunks) * 100))
    }

    const startedAt = Date.now()
    const alreadyUploadedBytes = done.size * task.chunkSize
    let bytesSent = 0

    for (let index = 0; index < task.totalChunks; index += 1) {
      if (task.status === 'paused') {
        return
      }
      if (done.has(index)) {
        continue
      }

      const from = index * task.chunkSize
      const to = Math.min(file.size, from + task.chunkSize)
      const blob = file.slice(from, to)

      await withRetry(() => api.uploadChunk(uploadId, index, blob, `${file.name}.part${index}`))

      done.add(index)
      bytesSent += blob.size
      task.uploadedChunks = done.size
      updateProgress(task)

      const elapsed = (Date.now() - startedAt) / 1000
      if (elapsed > 0.5) {
        task.bytesPerSecond = (bytesSent + alreadyUploadedBytes) / elapsed
      }
    }

    const completed = await api.completeUpload(uploadId)
    clearResumeId(file)
    task.uploadId = undefined
    task.progress = 100
    task.status = completed.result.duplicated ? 'duplicated' : 'completed'
    task.result = completed.result
    onImported(completed.result)
  }

  function updateProgress(task: UploadTask): void {
    task.progress = task.totalChunks
      ? Math.min(100, Math.floor((task.uploadedChunks / task.totalChunks) * 100))
      : 0
  }

  async function withRetry(action: () => Promise<unknown>): Promise<void> {
    let attempt = 0
    for (;;) {
      try {
        await action()
        return
      } catch (error) {
        attempt += 1
        if (attempt >= MAX_CHUNK_RETRIES) {
          throw error
        }
        await sleep(400 * attempt)
      }
    }
  }

  function pause(task: UploadTask): void {
    if (task.status === 'uploading') {
      task.status = 'paused'
    }
  }

  function resume(task: UploadTask): void {
    if (task.status === 'paused' || task.status === 'failed') {
      task.status = 'queued'
      task.error = undefined
      pump()
    }
  }

  async function cancel(task: UploadTask): Promise<void> {
    const index = tasks.indexOf(task)
    if (index >= 0) {
      tasks.splice(index, 1)
    }
    if (task.uploadId) {
      try {
        await api.cancelUpload(task.uploadId)
      } catch {
        // the session TTL will clean it up anyway
      }
    }
    clearResumeId(task.file)
    pump()
  }

  function clearFinished(): void {
    for (let i = tasks.length - 1; i >= 0; i -= 1) {
      const task = tasks[i]
      if (task && (task.status === 'completed' || task.status === 'duplicated')) {
        tasks.splice(i, 1)
      }
    }
  }

  return { tasks, running, add, pause, resume, cancel, clearFinished }
}
