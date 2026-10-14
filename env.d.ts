/// <reference types="vite/client" />

interface ImportMetaEnv {
  /** REST base URL; defaults to /api (proxied to the Spring Boot server by Vite). */
  readonly VITE_API_BASE_URL?: string
  /** Optional UI title shown in the header. */
  readonly VITE_APP_TITLE?: string
}

interface ImportMeta {
  readonly env: ImportMetaEnv
}
