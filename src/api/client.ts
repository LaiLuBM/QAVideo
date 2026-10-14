import type { ApiResponse } from './types'

/** REST base URL. Defaults to /api, which Vite proxies to the Spring Boot server. */
const RAW_BASE = (import.meta.env.VITE_API_BASE_URL ?? '/api').replace(/\/+$/, '')

/** Normalised API failure: HTTP status + the backend business code. */
export class ApiError extends Error {
  readonly code: number
  readonly status: number

  constructor(code: number, message: string, status: number) {
    super(message)
    this.name = 'ApiError'
    this.code = code
    this.status = status
  }
}

async function toData<T>(response: Response): Promise<T> {
  const text = await response.text()
  let payload: ApiResponse<T> | null = null
  if (text) {
    try {
      payload = JSON.parse(text) as ApiResponse<T>
    } catch {
      payload = null
    }
  }

  if (!response.ok || (payload && payload.code !== 0)) {
    const message = payload?.message ?? (response.statusText || `HTTP ${response.status}`)
    throw new ApiError(payload?.code ?? response.status, message, response.status)
  }
  if (!payload) {
    throw new ApiError(50000, '服务端返回了非 JSON 响应', response.status)
  }
  return payload.data
}

export type QueryParams = Record<string, string | number | boolean | undefined | null>

function withQuery(path: string, params?: QueryParams): string {
  if (!params) {
    return path
  }
  const query = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value !== undefined && value !== null && value !== '') {
      query.set(key, String(value))
    }
  })
  const suffix = query.toString()
  return suffix ? `${path}?${suffix}` : path
}

export const http = {
  get<T>(path: string, params?: QueryParams): Promise<T> {
    return fetch(`${RAW_BASE}${withQuery(path, params)}`).then((r) => toData<T>(r))
  },
  post<T>(path: string, body?: unknown): Promise<T> {
    return fetch(`${RAW_BASE}${path}`, {
      method: 'POST',
      headers: body === undefined ? undefined : { 'Content-Type': 'application/json' },
      body: body === undefined ? undefined : JSON.stringify(body),
    }).then((r) => toData<T>(r))
  },
  postForm<T>(path: string, form: FormData): Promise<T> {
    return fetch(`${RAW_BASE}${path}`, { method: 'POST', body: form }).then((r) => toData<T>(r))
  },
  delete<T>(path: string): Promise<T> {
    return fetch(`${RAW_BASE}${path}`, { method: 'DELETE' }).then((r) => toData<T>(r))
  },
}

/**
 * Turns a backend-relative media path into something the <video> tag can load.
 * When the API is served from another origin the path is prefixed accordingly.
 */
export function mediaUrl(playUrl?: string | null): string | undefined {
  if (!playUrl) {
    return undefined
  }
  if (RAW_BASE.startsWith('http')) {
    const origin = RAW_BASE.replace(/\/api$/, '')
    return `${origin}${playUrl}`
  }
  return playUrl
}
