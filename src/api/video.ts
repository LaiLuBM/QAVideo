import { http } from './client'
import type {
  PageResult,
  SystemStatusDto,
  TaskDto,
  UploadChunkResponse,
  UploadCompleteResponse,
  UploadInitResponse,
  UploadSessionDto,
  VideoBriefDto,
  VideoDetailDto,
  VideoImportResponse,
  VideoStatus,
} from './types'

export interface ListQuery {
  page?: number
  size?: number
  status?: VideoStatus | ''
  keyword?: string
}

export interface UploadInitPayload {
  fileName: string
  fileSize: number
  contentType?: string
  chunkSize?: number
  uploadId?: string
  title?: string
  contentHash?: string
  autoParse?: boolean
}

export const api = {
  // ---- library ------------------------------------------------------------
  listVideos(query: ListQuery = {}): Promise<PageResult<VideoBriefDto>> {
    return http.get<PageResult<VideoBriefDto>>('/videos', { ...query })
  },

  videoDetail(id: number): Promise<VideoDetailDto> {
    return http.get<VideoDetailDto>(`/videos/${id}`)
  },

  importUrl(url: string, title?: string, autoParse = true): Promise<VideoImportResponse> {
    return http.post<VideoImportResponse>('/videos/import-url', { url, title, autoParse })
  },

  reparse(id: number): Promise<VideoImportResponse> {
    return http.post<VideoImportResponse>(`/videos/${id}/parse`)
  },

  deleteVideo(id: number): Promise<void> {
    return http.delete<void>(`/videos/${id}`)
  },

  task(id: number): Promise<TaskDto> {
    return http.get<TaskDto>(`/tasks/${id}`)
  },

  // ---- resumable upload ---------------------------------------------------
  initUpload(payload: UploadInitPayload): Promise<UploadInitResponse> {
    return http.post<UploadInitResponse>('/uploads/init', payload)
  },

  uploadChunk(
    uploadId: string,
    index: number,
    blob: Blob,
    fileName: string,
  ): Promise<UploadChunkResponse> {
    const form = new FormData()
    form.append('file', blob, fileName)
    return http.postForm<UploadChunkResponse>(`/uploads/${uploadId}/chunks/${index}`, form)
  },

  uploadSession(uploadId: string): Promise<UploadSessionDto> {
    return http.get<UploadSessionDto>(`/uploads/${uploadId}`)
  },

  completeUpload(uploadId: string): Promise<UploadCompleteResponse> {
    return http.post<UploadCompleteResponse>(`/uploads/${uploadId}/complete`)
  },

  cancelUpload(uploadId: string): Promise<void> {
    return http.delete<void>(`/uploads/${uploadId}`)
  },

  // ---- diagnostics --------------------------------------------------------
  systemStatus(): Promise<SystemStatusDto> {
    return http.get<SystemStatusDto>('/system/status')
  },
}
