# QAVideo · AI 视频解析 - 总结平台

> 拖入视频（或粘贴链接）→ 后端分片接收并去重入库 → RocketMQ 异步投递解析任务 →
> LangChain4j(DeepSeek) 生成总结/要点/章节 → MySQL 落库 → 前端实时展示进度与结果。

前端 **Vue 3 + TypeScript + Vite**，后端 **Java 21 + Spring Boot 3.5**，
数据 **MySQL**，缓存/断点续传 **Redis**，异步 **RocketMQ**，Agent **LangChain4j（DeepSeek API 字段已预留）**。

---

## 1. 功能一览

| 能力 | 说明 |
| --- | --- |
| 链接导入 | 粘贴任意视频链接；链接先规范化（去掉 `utm_*`、`spm`、`fbclid`、分享参数、fragment、参数顺序）再哈希去重 |
| 本地导入 | **整个窗口都是拖拽区**，文件拖进来即上传；也支持点击选择文件 |
| 分片上传 | 默认 5&nbsp;MB/片，单片失败可重传，不重传整文件 |
| 断点续传 | 服务端（Redis）记录已落盘分片，刷新页面/断网/关掉浏览器后再拖同一个文件，自动从断点继续 |
| 秒传去重 | 浏览器若已知文件 sha256，`/uploads/init` 直接命中已有记录，一个字节都不用传 |
| 内容去重 | 合并后计算 sha256，`video.dedup_key` 唯一索引兜底，并发导入也只会有一条记录 |
| 异步解析 | 导入接口毫秒级返回；解析任务走 RocketMQ（或本地线程池），任务进度写入 MySQL 供前端轮询 |
| AI 解析 | LangChain4j `AiServices` + OpenAI 兼容客户端调用 DeepSeek，产出总结 / 关键词 / 要点 / 章节 |
| 无密钥可用 | 未配置 `DEEPSEEK_API_KEY` 时自动降级为本地抽取式摘要（结果标记 `mockAi=true`，前端明确提示） |
| 素材提取 | **两条路**：有人讲解 → 语音识别（ASR）；没人讲解（游戏集锦/风景/比赛）→ 画面理解（抽帧 + 视觉模型）。详见 6.7 / 6.8 |
| 媒体播放 | 支持 HTTP Range 的 `/api/videos/{id}/stream`，前端可直接拖动进度条播放 |

---

## 2. 架构

```
┌──────────────────────────── 浏览器 (Vue 3) ────────────────────────────┐
│  窗口级拖拽区 DropZone │ 分片上传引擎 useUploader │ 任务进度轮询        │
└───────────────┬────────────────────────────────────────────────────────┘
                │ REST /api/**   (Vite dev proxy → 8080)
┌───────────────▼──────────────────── Spring Boot ───────────────────────┐
│  Controller 层   VideoController / UploadController / TaskController    │
│  Service 层      VideoService(去重)  UploadService(分片合并)             │
│                  VideoParsePipeline(ACQUIRE→TRANSCRIBE→SUMMARIZE→PERSIST)│
│  MQ 层           ParseTaskDispatcher ─┬─ RocketMqParseTaskDispatcher    │
│                                       └─ LocalParseTaskDispatcher        │
│  Agent 层        LangChain4j AiServices → DeepSeek / MockSummaryEngine   │
└───┬───────────────┬──────────────────────┬─────────────────────────────┘
    │               │                      │
┌───▼────┐   ┌──────▼──────┐        ┌──────▼─────────┐
│ MySQL  │   │   Redis     │        │  RocketMQ      │
│ 元数据 │   │ 分片状态/锁 │        │ 解析任务队列    │
│ 去重键 │   │ 断点续传    │        │ 削峰/重试       │
└────────┘   └─────────────┘        └────────────────┘
```

### 目录结构

```
QAVideo/
├─ src/                          # Vue 3 前端
│  ├─ api/                       #   client.ts(统一请求/错误) types.ts(video.ts 接口)
│  ├─ components/                #   AppHeader / ImportPanel / DropZone / UploadQueue
│  │                             #   VideoLibrary / VideoDetail / StatusPill
│  ├─ composables/
│  │  ├─ useUploader.ts          #   分片上传 + 重试 + 暂停/续传 + 秒传
│  │  └─ useVideoLibrary.ts      #   列表/详情/筛选 + 任务进度轮询
│  ├─ utils/format.ts            #   字节/时长/时间/阶段文案
│  └─ styles/main.css            #   设计令牌与通用组件样式
├─ backend/                      # Spring Boot 后端
│  ├─ src/main/java/com/qavideo/
│  │  ├─ config/                 #   AppProperties / WebConfig(CORS) / AsyncConfig
│  │  ├─ controller/             #   Video / Upload / Task / System
│  │  ├─ domain/ repository/     #   Video / ParseTask 实体与 JPA 仓储
│  │  ├─ dto/                    #   请求响应模型 + VideoMapper
│  │  ├─ service/                #   业务编排 + ai/ 引擎 + 转写
│  │  ├─ mq/                     #   投递器与消费者（RocketMQ / 本地）
│  │  ├─ storage/                #   本地内容寻址存储 + 上传会话状态
│  │  ├─ lock/                   #   Redis / 内存分布式锁
│  │  └─ media/                  #   直链媒体下载 + URL 规范化去重
│  └─ src/test/java/             #   单元测试 + 端到端集成测试
├─ sql/init.sql                  # MySQL 建库建表（与实体一致）
├─ docker-compose.yml            # MySQL + Redis + RocketMQ + Dashboard
└─ docker/rocketmq/broker.conf
```

---

## 3. 快速开始

### 前置

- Node.js ≥ 22.18（工程 `engines` 要求）、JDK ≥ 21（已在 **JDK 25** 上验证）
- **方式 B 需要 Docker Desktop**（`docker compose` 起 MySQL/Redis/RocketMQ）；没装 Docker 请先用方式 A。
- Maven 无需安装，仓库带 `mvnw`；若 `mvnw` 报 `JAVA_HOME not found`，先设置：

```powershell
$env:JAVA_HOME = 'C:\Program Files\Java\jdk-25'   # 改成你的 JDK 路径
```

> 若忘了启动 MySQL 就执行方式 B，启动会失败并打印 `APPLICATION FAILED TO START` +
> 「连不上 MySQL 数据库（Connection refused）」以及三种解决办法（见
> `DatabaseUnavailableFailureAnalyzer`），不用再去翻 Hibernate 堆栈。

### 方式 A：零依赖体验（推荐先用这个跑通全流程）

后端使用 `standalone` profile：H2 内存库 + 内存分片状态 + 本地线程池 + 本地兜底摘要，
**不需要 MySQL / Redis / RocketMQ**。

```bash
# 终端 1：后端
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=standalone
# Windows: mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=standalone"

# 终端 2：前端
npm install
npm run dev
```

打开 <http://localhost:5173>：拖一个视频文件进窗口，或粘贴一个链接。

> `standalone` 用的是 H2 **内存**库，进程一停数据就没了。想在不装 Docker 的前提下保留数据，用下面的方式 A+。

### 方式 A+：不装 Docker，但数据持久化（推荐没有 Docker 时使用）
同样不需要 MySQL / Redis / RocketMQ，只是把 H2 落到本地文件：

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.profiles=local-file
```

- 数据库文件：`backend/data/h2/qavideo.mv.db`（删除该目录即重置数据库）
- 媒体文件仍在 `backend/data/storage`，断点续传状态在内存中（重启后已传分片按磁盘对账恢复）
- 已验证：导入视频 → 重启进程 → 视频库数据仍在

### 方式 B：完整中间件（生产语义，需要 Docker Desktop）

```bash
docker compose up -d            # MySQL:3306 Redis:6379 RocketMQ:9876/10911 Dashboard:8180

cd backend
# Windows PowerShell 逐条设置环境变量
$env:QVIDEO_REDIS_ENABLED='true'
$env:QVIDEO_MQ_ENABLED='true'
$env:DEEPSEEK_API_KEY='sk-xxxxxxxx'      # 不设置则走本地兜底摘要
./mvnw spring-boot:run
```

> `./mvnw spring-boot:run` 失败且提示 `Process terminated with exit code: 1` 时，
> 真正的堆栈在这行之上。最常见的原因就是 MySQL 没起来（`docker` 命令不存在 / Docker Desktop 未启动），
> 此时改用方式 A 即可，或先安装并启动 Docker Desktop。

默认连接串：MySQL `root/root@localhost:3306/qavideo`、Redis `localhost:6379`、
RocketMQ `127.0.0.1:9876`（可用环境变量覆盖，见下表）。

### 方式 C：只起 MySQL，其余降级

```bash
docker compose up -d mysql
cd backend && ./mvnw spring-boot:run     # 默认 profile：MySQL 落库，Redis/MQ 走内存与线程池
```

### 生产打包

```bash
cd backend && ./mvnw clean package        # → backend/target/qavideo-backend.jar
npm run build                             # → dist/（静态资源，交给 Nginx 或 Spring Boot 托管）
```

---

## 4. 配置项

全部通过环境变量覆盖，配置文件见 `backend/src/main/resources/application.yml`。

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `SERVER_PORT` | `8080` | 后端端口 |
| `MYSQL_URL` / `MYSQL_USER` / `MYSQL_PASSWORD` | `...localhost:3306/qavideo` / `root` / `root` | 数据库连接（管理视频数据与去重） |
| `REDIS_HOST` / `REDIS_PORT` / `REDIS_PASSWORD` | `localhost` / `6379` / 空 | Redis 连接 |
| `QVIDEO_REDIS_ENABLED` | `false` | `true`：分片状态、分布式锁走 Redis（多实例必须开启） |
| `QVIDEO_MQ_ENABLED` | `false` | `true`：解析任务投递到 RocketMQ；`false`：本地线程池 |
| `ROCKETMQ_NAME_SERVER` | `127.0.0.1:9876` | NameServer 地址 |
| `QVIDEO_MQ_TOPIC` / `QVIDEO_MQ_CONSUMER_GROUP` | `qavideo-parse-topic` / `qavideo-parse-consumer` | 主题与消费组 |
| `DEEPSEEK_API_KEY` | 空 | **预留字段**：填入后自动启用 DeepSeek |
| `DEEPSEEK_BASE_URL` | `https://api.deepseek.com/v1` | OpenAI 兼容地址 |
| `DEEPSEEK_MODEL` | `deepseek-chat` | 模型名（可换 `deepseek-reasoner`） |
| `DEEPSEEK_TEMPERATURE` / `DEEPSEEK_TIMEOUT` / `DEEPSEEK_MAX_RETRIES` | `0.3` / `120s` / `2` | 采样与重试 |
| `QVIDEO_ASR_PROVIDER` | `none` | 语音识别：`none` / `openai`（兼容 OpenAI 的转写接口）/ `command`（本地 whisper 等 CLI） |
| `QVIDEO_ASR_BASE_URL` | `https://api.openai.com/v1` | 转写接口地址（OpenAI / 硅基流动 / 自建 faster-whisper-server 均可） |
| `QVIDEO_ASR_API_KEY` | 空 | 转写接口密钥 |
| `QVIDEO_ASR_MODEL` | `whisper-1` | 转写模型名 |
| `QVIDEO_ASR_LANGUAGE` | `zh` | 识别语言 |
| `QVIDEO_ASR_MAX_FILE_SIZE` | `26214400`（25MB） | 超出后跳过 ASR 并在占位文本中说明原因 |
| `QVIDEO_ASR_COMMAND` | 空 | `command` 模式命令模板，占位符 `{file}` `{outDir}` `{language}` |
| `QVIDEO_VISION_PROVIDER` | `none` | 画面理解：`none` / `openai`（兼容 OpenAI 的视觉模型） |
| `QVIDEO_VISION_BASE_URL` | `https://api.openai.com/v1` | 视觉模型地址（GPT-4o / 通义千问 qwen-vl / 智谱 GLM-4V / 硅基流动） |
| `QVIDEO_VISION_API_KEY` | 空 | 视觉模型密钥 |
| `QVIDEO_VISION_MODEL` | `gpt-4o-mini` | 视觉模型名（必须支持图片输入） |
| `QVIDEO_VISION_MAX_FRAMES` | `8` | 最多抽多少帧（每帧=一次图片输入，直接影响花费） |
| `QVIDEO_FFMPEG_PATH` / `QVIDEO_FFPROBE_PATH` | `ffmpeg` / `ffprobe` | 抽帧工具路径（不在 PATH 时显式指定） |
| `QVIDEO_CHUNK_SIZE` | `5242880` | 分片大小（字节） |
| `QVIDEO_MAX_FILE_SIZE` | `2147483648` | 单文件上限（2&nbsp;GB） |
| `QVIDEO_UPLOAD_TTL` | `24h` | 未完成上传会话的保留时长 |
| `QVIDEO_STORAGE_ROOT` | `./data/storage` | 媒体文件目录（内容寻址 `yyyy/MM/{sha256}.ext`） |
| `QVIDEO_UPLOAD_TEMP_ROOT` | `./data/uploads` | 分片临时目录 |
| `QVIDEO_CORS_ORIGINS` | `http://localhost:5173,...` | 允许的前端来源 |

前端：`VITE_API_BASE_URL`（默认 `/api`，走 Vite 代理）、`VITE_APP_TITLE`，见 `.env.example`。

---

## 5. API

统一响应体：`{"code":0,"message":"ok","data":...}`，`code!=0` 即业务错误。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/videos/import-url` | 链接导入，body `{url,title?,autoParse?}`，命中去重返回 `duplicated=true` |
| GET | `/api/videos` | 视频库分页：`page,size,status,keyword` |
| GET | `/api/videos/{id}` | 详情：总结、转写、关键词、要点、章节、最近任务 |
| GET | `/api/videos/{id}/stream` | 播放媒体文件（支持 `Range`，可拖动进度条） |
| GET | `/api/videos/{id}/tasks` | 该视频的解析历史 |
| POST | `/api/videos/{id}/parse` | 重新解析（换模型、补字幕后再跑一遍） |
| DELETE | `/api/videos/{id}` | 删除记录与本地文件 |
| POST | `/api/uploads/init` | 创建/恢复上传会话，返回 `uploadId,chunkSize,totalChunks,uploadedChunks,resumed,duplicate` |
| GET | `/api/uploads/{uploadId}` | **断点续传查询**：服务端已持有的分片、进度 |
| POST | `/api/uploads/{uploadId}/chunks/{index}` | 上传第 `index` 片（`multipart/form-data`，字段名 `file`），重复上传同一片安全 |
| POST | `/api/uploads/{uploadId}/complete` | 合并 → sha256 → 去重 → 入库 → 投递解析任务 |
| DELETE | `/api/uploads/{uploadId}` | 取消上传并清理分片 |
| GET | `/api/tasks/{id}` | 任务进度（`status/progress/stage/errorMessage/model/mockAi`） |
| GET | `/api/system/status` | 中间件与 AI 模式诊断（前端顶部状态条） |

示例：

```bash
# 链接导入
curl -X POST http://localhost:8080/api/videos/import-url \
  -H 'Content-Type: application/json' \
  -d '{"url":"https://www.bilibili.com/video/BV1xx411c7mD?spm_id_from=333.999"}'

# 分片上传
curl -X POST http://localhost:8080/api/uploads/init \
  -H 'Content-Type: application/json' \
  -d '{"fileName":"demo.mp4","fileSize":10485760,"contentType":"video/mp4"}'
curl -F "file=@chunk0.part" http://localhost:8080/api/uploads/<uploadId>/chunks/0
curl http://localhost:8080/api/uploads/<uploadId>          # 查看已收分片（断点续传）
curl -X POST http://localhost:8080/api/uploads/<uploadId>/complete
```

---

## 6. 关键设计

### 6.1 去重（MySQL）

1. 链接：`UrlNormalizer` 规范化 → `dedup_key = "url:" + sha256(规范化链接)`。
2. 文件：分片合并时顺带算出 `sha256` → `dedup_key = "file:" + sha256`。
3. 存储路径本身是内容寻址 `{root}/yyyy/MM/{sha256}.{ext}`，相同内容天然落在同一路径。
4. `video.dedup_key` 唯一索引是最终仲裁：并发导入时后到者捕获
   `DataIntegrityViolationException` 并返回已有记录（`duplicated=true`），不会报错、不会产生脏数据。

### 6.2 断点续传（Redis）

- 会话元数据：HASH `qavideo:upload:{id}`；已收分片：SET `qavideo:upload:{id}:chunks`（`SADD` 原子，多片并发不丢进度），两者共享 TTL。
- `GET /api/uploads/{id}` 返回已收分片，前端只补缺失的片；每片独立重试 3 次。
- 页面刷新后浏览器凭 `localStorage` 中的会话 id 继续；即使 Redis 被清空，`/uploads/init`
  也会用磁盘上残留的 `chunk-*.part` 反向对账，尽量保住已传字节。
- 定时任务清理超过 TTL 的孤儿会话目录（集群下用分布式锁保证只有一个实例执行）。

### 6.3 异步化（RocketMQ）

- 导入接口只做「去重 + 落库 + 投递」，立即返回，解析在消费者里进行。
- 投递发生在**事务提交之后**（`TransactionSynchronization.afterCommit`），避免消费者读不到刚写入的任务行。
- `parse_task.request_id` 作为幂等键：消息重投时若任务已 `SUCCESS` 直接跳过；
  `markRunning` 用状态机保证同一任务不会被并发执行两次。
- 未部署 RocketMQ 时自动切换到本地线程池（`LocalParseTaskDispatcher`），接口行为完全一致。
- **中间件不可用时的行为**：消费者由应用自己托管（`SmartLifecycle` + 守护线程退避重连），
  RocketMQ 没就绪时不会拖垮启动；`/api/system/status` 的 `extras.mqConsumerConnected` 会如实反映连接状态。
  此时投递重试 3 次后把任务标记为 `FAILED` 并写明原因（不静默丢失、也不会把接口卡住几十秒），
  broker 恢复后在界面上点「重新解析」即可。

### 6.4 LangChain4j + DeepSeek

```java
public interface VideoSummaryAgent {
    @SystemMessage(fromResource = "prompts/summary-system.txt")   // 提示词外置可改
    Result<String> analyze(@UserMessage String videoContext);     // Result 带 token 用量
}
// OpenAiChatModel(baseUrl=https://api.deepseek.com/v1, modelName=deepseek-chat, apiKey=${DEEPSEEK_API_KEY})
```

- DeepSeek 兼容 OpenAI 协议，因此复用 LangChain4j 的 OpenAI 客户端；换模型/换供应商只改配置。
- 返回的 JSON 会被容错解析（容忍 ```json 代码块与前后废话），字段：`summary / keywords /
  highlights / chapters / sentiment / targetAudience`。
- **API 字段预留**：`qavideo.ai.api-key` 为空时不会发起任何外部请求，改用本地抽取式摘要，
  并在任务上标记 `mockAi=true`，前端顶部与详情页都会明确提示，重新解析即可切换。
- 调用失败时（密钥错误、超时）同样降级为兜底摘要，并在总结末尾注明失败原因，避免整条链路崩掉。

### 6.5 转写 / ASR 接入点

`TranscriptService` 顺序：媒体文件旁的 `同名.srt / .vtt / .txt / .json` → 演示占位文本（可关）。
接真实 ASR（Whisper、阿里云智能语音、讯飞等）只需在解析前把识别结果写成同名字幕文件，
或替换 `TranscriptService#extract` —— 下游 Agent 与存储逻辑无需改动。

### 6.6 表结构归属（重要）MySQL 的表结构**不由 Hibernate 生成**：

- 权威 DDL：`backend/src/main/resources/db/schema-mysql.sql`（`CREATE TABLE IF NOT EXISTS`，应用启动时执行）；
- `sql/init.sql` 供 docker-compose 首次初始化使用，两者定义一致；
- 已有库升级用 `sql/repair-lob-columns.sql`；
- `ddl-auto: none`，Hibernate 只做读写，不改表结构。

原因是一次真实的线上事故：Hibernate 6.6 的 MySQLDialect 会把 `@Lob`/`CLOB` 的 String 渲染成
`tinytext`（255 字节），而 `ddl-auto: update` 会**主动 ALTER** 掉 `sql/init.sql` 建好的 `longtext` 列，
导致解析任务报 `Data truncation: Data too long for column 'highlights'`。
`SchemaContractTest` 会同时校验两个 SQL 文件把 LOB 列声明为 `LONGTEXT`/`TEXT`，并校验 MySQL 下
必须保持 `ddl-auto: none`，防止回归。

H2 的 `standalone` / `local-file` profile 不执行 MySQL DDL（`spring.sql.init.mode=never`），
仍由 Hibernate 建表（H2 的 CLOB 映射没有上述问题）。

### 6.7 语音识别（ASR）：直接喂视频，不必自己拆音频

关键事实：**DeepSeek 是纯文本模型**，读不了视频/音频/图片。想让总结基于真实内容，必须先有字幕，
而字幕只有两个来源：现成字幕文件，或语音识别。

**ASR 引擎会自己解出音轨**，所以不需要先用 ffmpeg 分离音频：Whisper 的
`/audio/transcriptions` 明确接受 `mp4/mpeg/m4a/wav/webm`，本地 whisper / whisper.cpp 拿到 mp4
也会内部调 ffmpeg 解码。本项目就是按这个前提实现的：**把媒体文件原样提交给 ASR**。

什么时候才需要自己抽音轨？只有：云接口有体积上限（OpenAI 25MB）、只需识别片段、长音频要分段并行。
这种情况一行 ffmpeg 即可：

```bash
ffmpeg -i input.mp4 -vn -ac 1 -ar 16000 -b:a 48k output.mp3
```

两种模式：

```bash
# 模式一：云端 / 自建 OpenAI 兼容转写接口
QVIDEO_ASR_PROVIDER=openai
QVIDEO_ASR_BASE_URL=https://api.openai.com/v1     # 或硅基流动 / 自建 faster-whisper-server
QVIDEO_ASR_API_KEY=sk-xxx
QVIDEO_ASR_MODEL=whisper-1

# 模式二：本地 CLI（无密钥、无体积上限，大视频推荐）
QVIDEO_ASR_PROVIDER=command
qavideo.asr.command=whisper --model small --language {language} --output_format srt --output_dir {outDir} {file}
```

识别结果会**缓存成媒体文件旁的 `<同名>.txt`**，同一视频再次解析不再重复转写（真实 `.srt/.vtt` 优先命中）。
未启用 / 调用失败 / 超出体积上限时会退回占位文本，并在文本中写明**具体原因**——
这样大模型不可能在假数据上编出看起来像真的总结。

自检：`GET /api/system/status` 的 `extras.asrProvider` / `extras.asrUnavailableReason`，
启动日志也会打印 `ASR enabled: provider=... model=...` 或不可用原因。

### 6.8 画面理解：让模型"看"视频（无旁白视频的正解）

游戏集锦、比赛、风景这类视频**没有人讲话**，ASR 转出来只有音效和空白。要总结它们只能看懂画面，
做法是"抽帧 + 视觉模型"：

```
视频.mp4
  │  ① ffmpeg 按时间均匀抽 N 帧（默认 8 帧，缩到 640px）
  ├──▶ ② 视觉模型逐帧描述画面（GPT-4o / 通义千问 qwen-vl / 智谱 GLM-4V / 硅基流动，任一 OpenAI 兼容接口）
  │
  └──▶ ③ DeepSeek 把"画面描述"汇总成总结        ← 与 ASR 路线共用同一个总结模型
```

注意：**DeepSeek 本身不能看图**，所以第 ② 步必须另外接一个多模态模型；第 ③ 步仍然是你配的 DeepSeek。

配置（需要本机有 ffmpeg）：

```bash
# Windows 安装 ffmpeg（二选一）：
#   conda install -n zsl -y ffmpeg          # 装进 conda 环境
#   winget install Gyan.FFmpeg              # 装到系统
# 若 ffmpeg 只装在 conda 环境里，用 backend/run.ps1 启动会自动把它的 Library\bin 加进 PATH
QVIDEO_VISION_PROVIDER=openai
QVIDEO_VISION_BASE_URL=https://dashscope.aliyuncs.com/compatible-mode/v1   # 通义千问（阿里云百炼）
QVIDEO_VISION_API_KEY=sk-xxx
QVIDEO_VISION_MODEL=qwen3-vl-plus      # 注意：模型名会随平台迭代变化，见下方提示
QVIDEO_VISION_MAX_FRAMES=8          # 帧越多越贵，8 帧足够概括几分钟的集锦
```

> **模型名别照抄旧文档。** 阿里云百炼已经把 `qwen-vl-plus` 之类的旧名称下线了
> （会返回 `404 Model not exist`）。用这条命令查你账号当前可用的模型名：
> ```bash
> curl -H "Authorization: Bearer $QVIDEO_VISION_API_KEY" \
>   https://dashscope.aliyuncs.com/compatible-mode/v1/models
> ```
> （代码在收到 404 时也会把这条命令直接写进报错信息里。）

> 更省事的做法：把 `backend/.env.example` 复制成 `backend/.env.local`，填好密钥，
> 然后 `cd backend; .\run.ps1` 启动 —— 脚本会自动加载这些变量、补 `JAVA_HOME`、
> 并从 conda 环境里找 ffmpeg（默认环境名 `zsl`，可用 `QVIDEO_CONDA_ENV` 改）。
> 这样就不会再出现「在别的终端设了环境变量、启动时却读不到」的问题。

常用服务商对应的 `BASE_URL`：

| 服务商 | `QVIDEO_VISION_BASE_URL` | 模型示例 |
| --- | --- | --- |
| 阿里云百炼（通义千问） | `https://dashscope.aliyuncs.com/compatible-mode/v1` | `qwen3-vl-plus`（旧名 `qwen-vl-plus` 已下线，会报 404） |
| 硅基流动 | `https://api.siliconflow.cn/v1` | `Qwen/Qwen2.5-VL-72B-Instruct` |
| 智谱 | `https://open.bigmodel.cn/api/paas/v4` | `glm-4v-plus` |
| OpenAI | `https://api.openai.com/v1` | `gpt-4o-mini` |

行为约定：

- 只有**音频转写不足**（少于 30 字）时才会走画面理解，避免有人讲解的视频白花视觉模型的钱；
- 抽帧顺带用 ffprobe 读出**时长**，回填 `duration_seconds`（此前这一列一直是空的）；
- 抽帧目录是临时目录，用完即删；
- ffmpeg 缺失时不会静默降级：占位文本里会写"未找到 ffmpeg…winget install Gyan.FFmpeg"。

自检：`GET /api/system/status` 的 `extras.visionProvider` / `extras.visionUnavailableReason`。

---

## 7. 测试与验证

```bash
cd backend
./mvnw test          # 9 个用例：URL 规范化、兜底摘要、端到端上传→解析→去重

cd ..
npm run build         # vue-tsc 类型检查 + 生产构建
```

端到端用例 `UploadFlowIntegrationTest` 会真实启动 Spring Boot（随机端口、H2、内存上传状态），
依次验证：`init → 只传 1 片 → 带 uploadId 重新 init（resumed=true, uploadedChunks=[0]）→
补齐分片 → complete → 异步任务 SUCCESS → 详情里有总结/转写/关键词 → 相同内容秒传与整体重传都只复用同一条记录`。

---

## 8. 常见问题

- **`.\run.ps1` 报「数组索引表达式丢失或无效 / Array index expression is missing」**：
  Windows PowerShell 5.1 在脚本**没有 BOM** 时会按本地代码页（GBK）解码，
  脚本里的中文注释变成乱码并撑坏语法。当前 `run.ps1` 已保存为 **UTF-8 with BOM**；
  用编辑器另存时请保留 BOM（VS Code 选 “UTF-8 with BOM”，或直接用 PowerShell 7 打开）。
  已验证在 Windows PowerShell 5.1 与 PowerShell 7 下都能正常运行。

- **解析报 `Data truncation: Data too long for column 'highlights' at row 1`**：
  Hibernate 6.6 的 MySQLDialect 会把 `@Lob/CLOB` 的 String 渲染成 `tinytext`（255 字节），
  而 `ddl-auto=update` 还会主动 `ALTER` 把已经正确的 `longtext` 列改小。
  现已修复：MySQL 表结构由 `backend/src/main/resources/db/schema-mysql.sql` 权威定义
  （`spring.sql.init.mode=always`，可重复执行），Hibernate 设为 `ddl-auto=none` 不再插手。
  **如果你的库是在修复前建的**，执行一次修复脚本即可（只加宽列，不动数据）：
  ```bash
  docker exec -i qavideo-mysql mysql -uroot -proot qavideo < sql/repair-lob-columns.sql
  ```
  然后重启后端。已有回归测试 `SchemaContractTest` 守住这个契约。
- **`qavideo-rmqbroker` 一直 Restarting**：镜像以 uid 3000 运行，而镜像内没有
  `/home/rocketmq/store`、`/home/rocketmq/logs`；空命名卷会以 root 属主挂上去，broker 连日志都写不了，
  启动失败后在 shutdown 阶段抛 NPE 导致无限重启。当前 compose 已不再给这两个路径挂命名卷。
  已存在的坏容器重建一次即可：`docker compose up -d --force-recreate rmqnamesrv rmqbroker`。
- **`mvnw` 报 JAVA_HOME 未设置**：`$env:JAVA_HOME='C:\Program Files\Java\jdk-25'`。
- **`spring-boot:run` 报 `Process terminated with exit code: 1`**：这是 Maven 包装后的退出码，
  原因在上面的堆栈里。最常见是 MySQL 未启动（本机没装 Docker / 容器没起来），
  按提示改用 `-Dspring-boot.run.profiles=standalone`，或先 `docker compose up -d`。
- **`docker: command not found`（已装 Docker Desktop 仍然报错）**：Docker Desktop 会把
  `%LOCALAPPDATA%\Programs\DockerDesktop\resources\bin` 写进**用户 PATH**，但**已经打开的终端不会自动刷新**。
  关掉终端重开即可；或在当前 Git Bash 里临时生效：
  ```bash
  export PATH="$PATH:/c/Users/<你的用户名>/AppData/Local/Programs/DockerDesktop/resources/bin"
  docker version      # 能输出版本即成功
  ```
- **设置了 `DEEPSEEK_API_KEY` 仍显示「未配置」**：
  1. 环境变量是**进程启动时**读取的，改完必须重启后端；
  2. 必须保证「设置变量的地方」和「启动后端的地方」是同一个会话。在 bash 里 `export` 只对该窗口有效，
     用 `setx` / 系统设置写入的变量需要**新开终端**才可见；IDE 里的 Run Configuration 也不会继承你终端的 `export`；
  3. 自检：在即将启动后端的那个终端里执行 `echo "$DEEPSEEK_API_KEY"`（bash）或
     `[Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY','User')`（PowerShell），为空就是没设置成功；
  4. 永久设置（PowerShell，改完重开终端）：
     ```powershell
     [Environment]::SetEnvironmentVariable('DEEPSEEK_API_KEY','sk-你的真实key','User')
     ```
  5. 启动成功的标志：日志出现 `LangChain4j agent ready: ... apiKeyConfigured=true`，
     或 `GET /api/system/status` 返回 `"aiApiKeyConfigured": true`。
- **临时验证密钥可用性（不想动环境变量）**：
  ```powershell
  .\mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local-file" "-Dspring-boot.run.arguments=--qavideo.ai.api-key=sk-你的key"
  ```
  注意命令会进 shell 历史，仅建议临时排查使用。
- **给 `spring-boot:run` 传 `-Dspring.datasource.url=...` 没生效**：这类 `-D` 只是 Maven 自己的
  系统属性，不会传给 fork 出来的应用进程。请用 profile（`-Dspring-boot.run.profiles=...`）
  或 `-Dspring-boot.run.arguments=--xxx=yyy`，也可以直接设置环境变量（如 `MYSQL_URL`）。
- **上传大文件报 413**：后端 `spring.servlet.multipart.max-file-size`（默认 2&nbsp;GB）与
  反向代理的 `client_max_body_size` 都要放开；分片本身很小，通常只需放开代理限制。
- **`/api/system/status` 显示「内存模式」**：说明 `QVIDEO_REDIS_ENABLED=false`；
  多实例部署务必设为 `true`，否则断点续传状态不共享。
- **任务一直是失败、提示「无法投递解析任务到 RocketMQ」**：说明 `QVIDEO_MQ_ENABLED=true` 但 broker 不可达。
  先 `docker compose up -d rmqnamesrv rmqbroker`，再看 `/api/system/status` 的 `mqConsumerConnected`
  变为 `true`，然后重新解析；临时不需要异步时把 `QVIDEO_MQ_ENABLED` 设为 `false` 即可走本地线程池。
- **Maven 首次构建慢**：首次会下载依赖；`~/.m2` 有缓存后约十几秒。
- **前端直连后端（不走代理）**：把 `VITE_API_BASE_URL` 改成 `http://localhost:8080/api`，
  后端 CORS 已允许 5173/4173 端口，可用 `QVIDEO_CORS_ORIGINS` 追加。

---

## 9. 可扩展方向

- 接入真实 ASR（Whisper）与视频抽帧，补充封面、章节截图。
- 页面类链接接入 yt-dlp / 平台开放接口（`MediaFetcher#fetch` 返回文件即可，其余链路不变）。
- RocketMQ 事务消息 + 死信队列，配合重试策略做更严格的投递保证。
- 解析结果做 Redis 读缓存与向量化检索（同一视频跨实例命中缓存）。
- 前端增加批量导入、解析结果导出（Markdown / DOCX）与分享链接。
