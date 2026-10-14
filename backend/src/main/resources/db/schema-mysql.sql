-- ---------------------------------------------------------------------------
-- QAVideo 建表脚本（MySQL 用，应用启动时自动执行，可重复执行）
--
-- 为什么不用 Hibernate 的 ddl-auto 建表：
--   Hibernate 6.6 的 MySQLDialect 会把 @Lob / CLOB 的 String 渲染成 `tinytext`（上限 255 字节），
--   而 ddl-auto=update 还会主动执行 ALTER 把已经正确的 `longtext` 列改小，导致
--   “Data truncation: Data too long for column 'highlights'”。
--   因此：MySQL 的表结构以本文件为准（spring.sql.init），Hibernate 设为 ddl-auto=none，不再插手。
--
-- 与 sql/init.sql 的表定义保持一致（那个文件供 docker-compose 首次初始化使用）。
-- 已有库升级：执行 sql/repair-lob-columns.sql。
-- ---------------------------------------------------------------------------

CREATE TABLE IF NOT EXISTS `video`
(
    `id`               BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`            VARCHAR(512)          DEFAULT NULL COMMENT '标题',
    `source_type`      VARCHAR(16)  NOT NULL COMMENT 'URL=链接导入 / LOCAL=本地上传',
    `source_url`       VARCHAR(1024)         DEFAULT NULL COMMENT '原始链接',
    `storage_path`     VARCHAR(1024)         DEFAULT NULL COMMENT '媒体文件相对路径（内容寻址）',
    `file_name`        VARCHAR(512)          DEFAULT NULL COMMENT '原始文件名',
    `file_size`        BIGINT                DEFAULT NULL COMMENT '文件大小（字节）',
    `content_type`     VARCHAR(128)          DEFAULT NULL COMMENT 'MIME 类型',
    `duration_seconds` INT                   DEFAULT NULL COMMENT '时长（秒）',
    `cover_url`        VARCHAR(1024)         DEFAULT NULL COMMENT '封面地址',
    `dedup_key`        VARCHAR(128) NOT NULL COMMENT '唯一去重键：url:sha256 / file:sha256',
    `content_hash`     VARCHAR(64)           DEFAULT NULL COMMENT '内容 sha256',
    `status`           VARCHAR(16)  NOT NULL COMMENT 'IMPORTED/PARSING/PARSED/FAILED',
    `summary`          LONGTEXT              DEFAULT NULL COMMENT 'AI 总结（必须 LONGTEXT）',
    `transcript`       LONGTEXT              DEFAULT NULL COMMENT '字幕/转写文本（必须 LONGTEXT）',
    `keywords`         VARCHAR(512)          DEFAULT NULL COMMENT '关键词 JSON 数组',
    `highlights`       LONGTEXT              DEFAULT NULL COMMENT '要点 JSON 数组（必须 LONGTEXT）',
    `chapters`         LONGTEXT              DEFAULT NULL COMMENT '章节 JSON 数组（必须 LONGTEXT）',
    `created_at`       DATETIME(6)  NOT NULL COMMENT '导入时间',
    `updated_at`       DATETIME(6)  NOT NULL COMMENT '更新时间',
    `last_parsed_at`   DATETIME(6)           DEFAULT NULL COMMENT '最近解析成功时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_video_dedup_key` (`dedup_key`),
    KEY `idx_video_created_at` (`created_at`),
    KEY `idx_video_status` (`status`),
    KEY `idx_video_content_hash` (`content_hash`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='视频元数据与 AI 解析结果';

CREATE TABLE IF NOT EXISTS `parse_task`
(
    `id`                BIGINT      NOT NULL AUTO_INCREMENT COMMENT '主键',
    `video_id`          BIGINT      NOT NULL COMMENT '关联 video.id',
    `request_id`        VARCHAR(64) NOT NULL COMMENT '幂等键（与 MQ 消息一致）',
    `status`            VARCHAR(16) NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
    `progress`          INT         NOT NULL DEFAULT 0 COMMENT '进度 0-100',
    `stage`             VARCHAR(32)          DEFAULT NULL COMMENT 'ACQUIRE/TRANSCRIBE/SUMMARIZE/PERSIST',
    `error_message`     TEXT                 DEFAULT NULL COMMENT '失败原因（必须 TEXT 及以上）',
    `model`             VARCHAR(128)         DEFAULT NULL COMMENT '使用的模型',
    `mock_ai`           BIT(1)      NOT NULL DEFAULT b'0' COMMENT '是否本地兜底摘要',
    `prompt_tokens`     INT                  DEFAULT NULL,
    `completion_tokens` INT                  DEFAULT NULL,
    `transcript_chars`  INT                  DEFAULT NULL,
    `created_at`        DATETIME(6) NOT NULL,
    `started_at`        DATETIME(6)          DEFAULT NULL,
    `finished_at`       DATETIME(6)          DEFAULT NULL,
    `duration_ms`       BIGINT               DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_task_video_id` (`video_id`),
    KEY `idx_task_status` (`status`),
    KEY `idx_task_request_id` (`request_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='视频解析任务';
