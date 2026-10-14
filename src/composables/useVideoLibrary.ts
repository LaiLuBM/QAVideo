import { computed, onBeforeUnmount, ref, watch } from 'vue'
import { api } from '@/api/video'
import type { SystemStatusDto, VideoBriefDto, VideoDetailDto, VideoStatus } from '@/api/types'
import { isActiveTask } from '@/utils/format'

const POLL_INTERVAL_MS = 2500

/**
 * Library state: paging, filters, the selected video's detail, and the polling loop that
 * follows asynchronous parse tasks (RocketMQ workers) until they settle.
 */
export function useVideoLibrary() {
  const videos = ref<VideoBriefDto[]>([])
  const total = ref(0)
  const page = ref(0)
  const size = ref(8)
  const keyword = ref('')
  const statusFilter = ref<VideoStatus | ''>('')
  const loading = ref(false)
  const error = ref<string | null>(null)

  const detail = ref<VideoDetailDto | null>(null)
  const detailLoading = ref(false)
  const selectedId = ref<number | null>(null)

  const systemStatus = ref<SystemStatusDto | null>(null)

  let pollTimer: number | null = null

  const hasActiveWork = computed(() => {
    const listActive = videos.value.some(
      (video) => video.status === 'PARSING' || isActiveTask(video.task?.status),
    )
    const detailActive =
      !!detail.value && (detail.value.status === 'PARSING' || isActiveTask(detail.value.task?.status))
    return listActive || detailActive
  })

  const totalPages = computed(() => (size.value > 0 ? Math.ceil(total.value / size.value) : 0))

  async function load(): Promise<void> {
    loading.value = true
    error.value = null
    try {
      const result = await api.listVideos({
        page: page.value,
        size: size.value,
        status: statusFilter.value || undefined,
        keyword: keyword.value || undefined,
      })
      videos.value = result.items
      total.value = result.total
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
    } finally {
      loading.value = false
    }
  }

  async function loadDetail(id: number): Promise<void> {
    selectedId.value = id
    detailLoading.value = true
    try {
      detail.value = await api.videoDetail(id)
    } catch (err) {
      error.value = err instanceof Error ? err.message : String(err)
      detail.value = null
    } finally {
      detailLoading.value = false
    }
  }

  async function loadStatus(): Promise<void> {
    try {
      systemStatus.value = await api.systemStatus()
    } catch {
      systemStatus.value = null
    }
  }

  /** Quiet refresh used by the poller: no spinners, no filter jumps. */
  async function refreshQuietly(): Promise<void> {
    try {
      const result = await api.listVideos({
        page: page.value,
        size: size.value,
        status: statusFilter.value || undefined,
        keyword: keyword.value || undefined,
      })
      videos.value = result.items
      total.value = result.total
      if (selectedId.value !== null) {
        const fresh = await api.videoDetail(selectedId.value)
        detail.value = fresh
      }
    } catch {
      // transient network hiccups should not clear the UI
    }
  }

  function startPolling(): void {
    if (pollTimer !== null) {
      return
    }
    pollTimer = window.setInterval(() => {
      void refreshQuietly()
    }, POLL_INTERVAL_MS)
  }

  function stopPolling(): void {
    if (pollTimer !== null) {
      window.clearInterval(pollTimer)
      pollTimer = null
    }
  }

  watch(hasActiveWork, (active) => {
    if (active) {
      startPolling()
    } else {
      stopPolling()
    }
  })

  onBeforeUnmount(stopPolling)

  /** Call right after an import/re-parse so polling starts immediately. */
  async function noteImported(id?: number): Promise<void> {
    await load()
    if (id !== undefined) {
      await loadDetail(id)
    }
    if (hasActiveWork.value) {
      startPolling()
    }
  }

  async function remove(id: number): Promise<void> {
    await api.deleteVideo(id)
    if (selectedId.value === id) {
      selectedId.value = null
      detail.value = null
    }
    await load()
    await loadStatus()
  }

  async function reparse(id: number): Promise<void> {
    await api.reparse(id)
    await noteImported(id)
  }

  function select(id: number): void {
    void loadDetail(id)
  }

  function closeDetail(): void {
    selectedId.value = null
    detail.value = null
  }

  function setPage(next: number): void {
    page.value = Math.max(0, next)
    void load()
  }

  return {
    videos,
    total,
    totalPages,
    page,
    size,
    keyword,
    statusFilter,
    loading,
    error,
    detail,
    detailLoading,
    selectedId,
    systemStatus,
    hasActiveWork,
    load,
    loadDetail,
    loadStatus,
    noteImported,
    remove,
    reparse,
    select,
    closeDetail,
    setPage,
    stopPolling,
  }
}
