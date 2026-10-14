/**
 * Wire types mirroring the Spring Boot DTOs.
 *
 * The backend serialises with `default-property-inclusion: non_null`, so any field may
 * be absent rather than null - hence the optional markers.
 */

export interface ApiResponse<T> {
  code: number
  message: string
  data: T
}

export type VideoStatus = 'IMPORTED' | 'PARSING' | 'PARSED' | 'FAILED'
export type TaskStatus = 'PENDING' | 'RUNNING' | 'SUCCESS' | 'FAILED'
export type SourceType = 'URL' | 'LOCAL'

export interface TaskDto {
  id: number
  videoId: number
  status?: TaskStatus
  progress?: number
  stage?: string
  errorMessage?: string
  model?: string
  mockAi?: boolean
  promptTokens?: number
  completionTokens?: number
  transcriptChars?: number
  createdAt?: string
  startedAt?: string
  finishedAt?: string
  durationMs?: number
}

export interface ChapterDto {
  start?: string
  title?: string
  summary?: string
}

export interface VideoBriefDto {
  id: number
  title?: string
  sourceType?: SourceType
  sourceUrl?: string
  fileName?: string
  fileSize?: number
  durationSeconds?: number
  coverUrl?: string
  status?: VideoStatus
  summaryPreview?: string
  keywords?: string[]
  createdAt?: string
  updatedAt?: string
  lastParsedAt?: string
  task?: TaskDto
}

export interface VideoDetailDto extends VideoBriefDto {
  contentType?: string
  contentHash?: string
  summary?: string
  transcript?: string
  highlights?: string[]
  chapters?: ChapterDto[]
  playUrl?: string
  recentTasks?: TaskDto[]
}

export interface VideoImportResponse {
  video: VideoDetailDto
  duplicated: boolean
  task?: TaskDto
}

export interface UploadInitResponse {
  uploadId?: string
  fileName?: string
  fileSize?: number
  chunkSize?: number
  totalChunks?: number
  uploadedChunks?: number[]
  resumed?: boolean
  duplicate?: VideoImportResponse
}

export interface UploadSessionDto {
  uploadId: string
  fileName?: string
  fileSize?: number
  chunkSize?: number
  totalChunks?: number
  uploadedChunks?: number[]
  receivedChunks?: number
  progress?: number
}

export interface UploadChunkResponse {
  uploadId: string
  index: number
  receivedChunks: number
  totalChunks: number
  progress: number
  completed: boolean
}

export interface UploadCompleteResponse {
  uploadId: string
  contentHash?: string
  mergedChunks?: number
  result: VideoImportResponse
}

export interface PageResult<T> {
  items: T[]
  total: number
  page: number
  size: number
  totalPages: number
}

export interface SystemStatusDto {
  application?: string
  version?: string
  javaVersion?: string
  redisEnabled?: boolean
  redisReachable?: boolean
  rocketMqEnabled?: boolean
  rocketMqNameServer?: string
  taskTopic?: string
  aiProvider?: string
  aiModel?: string
  aiApiKeyConfigured?: boolean
  aiMockMode?: boolean
  transcriptDemoEnabled?: boolean
  uploadChunkSize?: number
  maxUploadSize?: number
  videoCount?: number
  pendingTaskCount?: number
  extras?: Record<string, unknown>
}
