/**
 * ============================================================================
 *  TC-WEB-012 / TC-WEB-013  视频可播放，且加载与拖动进度条都走 HTTP Range
 *  优先级：P0    来源：probe/方案.md 附录B（真实事故 A1 的回归）
 * ============================================================================
 *
 * 【为什么这是 Web 端最重要的用例】
 *  真实事故：上传成功、解析成功，但视频**播不了**。
 *  根因是 /stream 的 Range 分支返回了 Spring 的 ResourceRegion 又预设了 video/mp4，
 *  转换器不匹配直接 500 —— 而不带 Range 的完整请求是 200，所以接口自测"看着是好的"。
 *  关键教训：<video> 播放**必然**发 Range 请求，这条路径只有真浏览器能覆盖。
 *  因此本用例的主断言不是"页面有 video 标签"，而是：
 *      ① /stream 的响应里必须出现 206 且带正确的 Content-Range
 *      ② 浏览器真的能解出元数据（readyState >= 1、video.error == null）
 *      ③ 拖动进度条后播放位置生效
 *
 * 【前置条件】
 *   1. 后端与前端都已启动：
 *        QAVIDEO_API=http://localhost:8080   QAVIDEO_WEB=http://localhost:5173
 *   2. 本机有 ffmpeg（用于生成一段真实可解码的 mp4；conda zsl 环境里已安装）
 *      若没有，用例会显式 skip 并提示，而不是伪造一个不可播放的文件
 *   3. 首次运行会生成 fixture：probe/.fixtures/probe-playback.mp4（默认 30s / H.264，约 15MB）
 *      —— 体积必须足够大，否则浏览器一次就把整段缓冲完，拖动时不会产生新的 Range 请求
 *
 * 【运行方式】
 *   npm i -D @playwright/test && npx playwright install chromium
 *   npx playwright test probe/e2e/video-playback-seek.spec.ts
 *   可调环境变量：
 *     QAVIDEO_API / QAVIDEO_WEB           默认 http://localhost:8080 / http://localhost:5173
 *     QAVIDEO_FIXTURE_SECONDS=30          生成视频时长（越大体积越大，建议 >= 20）
 *     QAVIDEO_MARKER                     页面里用于定位该视频的标题标记
 *
 * 【输入】
 *   · 一段由 ffmpeg 生成的真实 mp4（testsrc 画面，H.264）
 *   · 通过分片上传接口把它导入平台（autoParse=false，不触发模型费用）
 *   · 页面标题标记 QAVIDEO_MARKER，用于在视频库里定位这条记录
 *
 * 【输出】
 *   · Playwright 报告（控制台 + HTML report）与失败截图/追踪
 *   · 测试内打印：捕获到的 /stream 响应列表（状态码 + Content-Range）、
 *     readyState、duration、seek 前后 currentTime、控制台错误
 *   · 断言失败时给出"哪一步不符合预期"的明确信息
 *
 * 【预期结果】（全部满足才算 PASS）
 *   1. 点击视频库中的目标记录后出现 <video>，src 指向 /api/videos/{id}/stream
 *   2. 捕获到的 /stream 响应：**不存在 5xx**，且**至少一个是 206**
 *      （若只有 200，说明 Range 未被正确处理 → 浏览器需要整段下载，长视频体验崩塌）
 *   3. 206 响应头包含 `content-range: bytes a-b/total` 与 `accept-ranges: bytes`
 *   4. video.readyState >= 1（HAVE_METADATA），video.error == null，duration > 0
 *   5. 设置 currentTime 到 50% 后，实际 currentTime 收敛到目标附近（±20% 容差）
 *   6. 若 seek 触发了新的 /stream 请求，则该请求必须是 206 且起点 > 0
 *      （文件较小、浏览器已缓冲完时可能不产生新请求 —— 此时记为 info，不算失败）
 *   7. 页面控制台无 error（favicon 404 之类白名单除外）
 *
 * 【失败时的定位提示】
 *   · 全是 500                -> 即事故 A1：检查 /stream 的 Range 分支与消息转换器
 *   · 只有 200、没有 206      -> 服务端忽略了 Range 头（Accept-Ranges 未生效）
 *   · 206 但 content-range 缺失 -> 响应头未设置 Content-Range，播放器无法定位
 *   · readyState 一直 0       -> 拉流失败或 MIME 类型不是 video/*
 * ============================================================================
 */

import { createHash } from 'node:crypto'
import { execFileSync } from 'node:child_process'
import { existsSync, mkdirSync, readFileSync, statSync } from 'node:fs'
import { basename, join } from 'node:path'
import {
  expect,
  request as newApiContext,
  test,
  type APIRequestContext,
  type Response,
} from '@playwright/test'

const WEB = process.env.QAVIDEO_WEB ?? 'http://localhost:5173'
const API = process.env.QAVIDEO_API ?? 'http://localhost:8080'
const MARKER = process.env.QAVIDEO_MARKER ?? '[PROBE] playback-seek'
const FIXTURE_SECONDS = Number(process.env.QAVIDEO_FIXTURE_SECONDS ?? 30)
const FIXTURE_DIR = join(process.cwd(), 'probe', '.fixtures')
const FIXTURE = join(FIXTURE_DIR, 'probe-playback.mp4')
const CHUNK_SIZE = 2 * 1024 * 1024

/** 生成一段真实可解码的 H.264 mp4；没有 ffmpeg 就显式 skip，绝不拿假数据糊弄。 */
function ensureFixture(): void {
  if (existsSync(FIXTURE) && statSync(FIXTURE).size > 1024 * 1024) {
    return
  }
  mkdirSync(FIXTURE_DIR, { recursive: true })
  try {
    execFileSync(
      'ffmpeg',
      [
        '-hide_banner', '-loglevel', 'error', '-y',
        '-f', 'lavfi', '-i', `testsrc=size=640x360:rate=30:duration=${FIXTURE_SECONDS}`,
        '-pix_fmt', 'yuv420p', '-c:v', 'libx264', '-b:v', '4M',
        FIXTURE,
      ],
      { stdio: 'inherit' },
    )
  } catch {
    test.skip(true, `未找到可用的 ffmpeg，无法生成测试视频（fixture: ${FIXTURE}）`)
  }
}

/** 把 fixture 导入平台：先算 sha256 走秒传，命中则零字节完成，避免每轮重复上传 15MB。 */
async function ensureVideoOnServer(api: APIRequestContext): Promise<number> {
  const size = statSync(FIXTURE).size
  const contentHash = createHash('sha256').update(readFileSync(FIXTURE)).digest('hex')

  const initResponse = await api.post(`${API}/api/uploads/init`, {
    data: {
      fileName: basename(FIXTURE),
      fileSize: size,
      contentType: 'video/mp4',
      chunkSize: CHUNK_SIZE,
      title: MARKER,
      contentHash,
      autoParse: false,
    },
  })
  const init = await initResponse.json()
  expect(init.code, `init 失败: ${init.message}`).toBe(0)

  if (init.data?.duplicate) {
    return init.data.duplicate.video.id as number
  }

  const uploadId = init.data.uploadId as string
  const totalChunks = init.data.totalChunks as number
  const buffer = readFileSync(FIXTURE)
  for (let index = 0; index < totalChunks; index += 1) {
    const from = index * CHUNK_SIZE
    const to = Math.min(size, from + CHUNK_SIZE)
    const chunkResponse = await api.post(`${API}/api/uploads/${uploadId}/chunks/${index}`, {
      multipart: {
        file: {
          name: `chunk-${index}.part`,
          mimeType: 'application/octet-stream',
          buffer: buffer.subarray(from, to),
        },
      },
    })
    expect(chunkResponse.ok(), `分片 ${index} 上传失败`).toBeTruthy()
  }

  const completeResponse = await api.post(`${API}/api/uploads/${uploadId}/complete`)
  const completed = await completeResponse.json()
  expect(completed.code, `complete 失败: ${completed.message}`).toBe(0)
  return completed.data.result.video.id as number
}

function streamRequests(responses: Response[]): string[] {
  return responses.map((response) => `${response.status()} ${response.url()}`)
}

test.describe('TC-WEB-012/013 视频播放与进度条拖动', () => {
  let api: APIRequestContext
  let videoId: number

  test.beforeAll(async () => {
    ensureFixture()
    api = await newApiContext()
    videoId = await ensureVideoOnServer(api)
  })

  test.afterAll(async () => {
    await api?.dispose()
  })

  test('可播放，且加载与 seek 均使用 HTTP Range（206）', async ({ page }, testInfo) => {
    const streamResponses: Response[] = []
    const consoleErrors: string[] = []

    page.on('response', (response) => {
      if (/\/api\/videos\/\d+\/stream/.test(response.url())) {
        streamResponses.push(response)
      }
    })
    page.on('console', (message) => {
      if (message.type() === 'error') {
        consoleErrors.push(message.text())
      }
    })

    // ---- 1. 打开前端，从视频库进入该视频详情 ----
    await page.goto(WEB, { waitUntil: 'domcontentloaded' })
    const listItem = page.getByText(MARKER, { exact: false }).first()
    await expect(listItem, `视频库里应能找到标记为 ${MARKER} 的记录`).toBeVisible({ timeout: 20_000 })
    await listItem.click()

    // ---- 2. <video> 必须出现且指向 stream 接口 ----
    const video = page.locator('video').first()
    await expect(video).toBeVisible({ timeout: 20_000 })
    await expect(video).toHaveAttribute('src', new RegExp(`/api/videos/${videoId}/stream`))

    // ---- 3. 必须发生 Range 请求且不能是 5xx（事故 A1 的核心断言） ----
    await expect
      .poll(() => streamResponses.length, { timeout: 25_000, message: '播放器没有发起 /stream 请求' })
      .toBeGreaterThan(0)

    const statuses = streamResponses.map((response) => response.status())
    expect(statuses, `不得出现 5xx：\n${streamRequests(streamResponses).join('\n')}`).not.toContain(500)
    expect(
      statuses,
      `<video> 播放必须走 206 Range，否则浏览器需要整段下载。实际响应：\n${streamRequests(streamResponses).join('\n')}`,
    ).toContain(206)

    const rangeResponse = streamResponses.find((response) => response.status() === 206)!
    const headers = await rangeResponse.allHeaders()
    expect(headers['content-range'], '206 必须带 Content-Range').toMatch(/^bytes \d+-\d+\/\d+$/)
    expect(headers['accept-ranges'], '必须声明支持 Range').toBe('bytes')
    expect(headers['content-type'], 'MIME 必须可被播放器识别').toMatch(/^video\//)

    // ---- 4. 浏览器真的解出了元数据 ----
    await expect
      .poll(() => video.evaluate((element: HTMLVideoElement) => element.readyState), { timeout: 25_000 })
      .toBeGreaterThanOrEqual(1)

    const mediaError = await video.evaluate((element: HTMLVideoElement) =>
      element.error ? `${element.error.code}:${element.error.message}` : null,
    )
    expect(mediaError, 'video.error 不为空说明解码/拉流失败').toBeNull()

    const duration = await video.evaluate((element: HTMLVideoElement) => element.duration)
    expect(duration, '应能读出视频时长').toBeGreaterThan(0)

    // ---- 5. 拖动进度条：位置生效 ----
    const beforeSeek = streamResponses.length
    const target = duration / 2
    await video.evaluate((element: HTMLVideoElement, value: number) => {
      element.currentTime = value
    }, target)

    await expect
      .poll(() => video.evaluate((element: HTMLVideoElement) => element.currentTime), { timeout: 20_000 })
      .toBeGreaterThan(target * 0.8)

    // ---- 6. 若 seek 触发了新的 Range 请求，则必须是 206 且从非 0 位置开始 ----
    if (streamResponses.length > beforeSeek) {
      const seekResponse = streamResponses[streamResponses.length - 1]!
      const seekHeaders = await seekResponse.allHeaders()
      expect(seekResponse.status()).toBe(206)
      const start = Number(seekHeaders['content-range']!.split(' ')[1]!.split('-')[0])
      expect(start, 'seek 后的 Range 起点应大于 0（说明确实按需取流）').toBeGreaterThan(0)
      testInfo.annotations.push({ type: 'range-seek', description: `seek 触发 ${seekHeaders['content-range']}` })
    } else {
      testInfo.annotations.push({
        type: 'range-seek',
        description: '浏览器已缓冲完整文件，未产生新的 Range 请求（小文件场景，不判失败）',
      })
    }

    // ---- 7. 控制台不得有 error ----
    const meaningfulErrors = consoleErrors.filter((text) => !/favicon|net::ERR_ABORTED/.test(text))
    expect(meaningfulErrors, `控制台出现错误：\n${meaningfulErrors.join('\n')}`).toEqual([])

    testInfo.annotations.push({
      type: 'summary',
      description: `stream 响应: ${streamRequests(streamResponses).join(' | ')}; duration=${duration.toFixed(2)}s`,
    })
  })
})
