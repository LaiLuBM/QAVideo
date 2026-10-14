-- ---------------------------------------------------------------------------
-- QAVideo schema (MySQL 8.x, utf8mb4)
--
-- 这套 DDL 与 backend 的 JPA 实体一一对应。应用默认 ddl-auto=update，可以自行建表；
-- 此脚本用于「先建库」或生产环境由 DBA 收口，字段/索引与实体保持一致。
--
--   mysql -uroot -p < sql/init.sql
-- ---------------------------------------------------------------------------

CREATE DATABASE IF NOT EXISTS `qavideo`
    DEFAULT CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE `qavideo`;

-- ===========================================================================
-- 视频：元数据（导入时间等）+ 去重键 + AI 解析结果
-- ===========================================================================
CREATE TABLE IF NOT EXISTS `video`
(
    `id`              BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `title`           VARCHAR(512)          DEFAULT NULL COMMENT '标题',
    `source_type`     VARCHAR(16)  NOT NULL COMMENT 'URL=链接导入 / LOCAL=本地上传',
    `source_url`      VARCHAR(1024)         DEFAULT NULL COMMENT '原始链接',
    `storage_path`    VARCHAR(1024)         DEFAULT NULL COMMENT '媒体文件相对路径（内容寻址）',
    `file_name`       VARCHAR(512)          DEFAULT NULL COMMENT '原始文件名',
    `file_size`       BIGINT                DEFAULT NULL COMMENT '文件大小（字节）',
    `content_type`    VARCHAR(128)          DEFAULT NULL COMMENT 'MIME 类型',
    `duration_seconds` INT                  DEFAULT NULL COMMENT '时长（秒）',
    `cover_url`       VARCHAR(1024)         DEFAULT NULL COMMENT '封面地址',
    -- 去重核心：链接为 url:sha256(规范化链接)，文件为 file:sha256(内容)
    `dedup_key`       VARCHAR(128) NOT NULL COMMENT '唯一去重键',
    `content_hash`    VARCHAR(64)           DEFAULT NULL COMMENT '内容 sha256（文件内容或规范化链接）',
    `status`          VARCHAR(16)  NOT NULL COMMENT 'IMPORTED/PARSING/PARSED/FAILED',
    `summary`         LONGTEXT              DEFAULT NULL COMMENT 'AI 总结',
    `transcript`      LONGTEXT              DEFAULT NULL COMMENT '字幕/转写文本',
    `keywords`        VARCHAR(512)          DEFAULT NULL COMMENT '关键词 JSON 数组',
    `highlights`      LONGTEXT              DEFAULT NULL COMMENT '要点 JSON 数组',
    `chapters`        LONGTEXT              DEFAULT NULL COMMENT '章节 JSON 数组',
    `created_at`      DATETIME(6)  NOT NULL COMMENT '导入时间',
    `updated_at`      DATETIME(6)  NOT NULL COMMENT '更新时间',
    `last_parsed_at`  DATETIME(6)           DEFAULT NULL COMMENT '最近解析成功时间',
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_video_dedup_key` (`dedup_key`),
    KEY `idx_video_created_at` (`created_at`),
    KEY `idx_video_status` (`status`),
    KEY `idx_video_content_hash` (`content_hash`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='视频元数据与 AI 解析结果';

-- ===========================================================================
-- 解析任务：异步任务状态机（RocketMQ 投递 / 本地线程池执行）
-- ===========================================================================
CREATE TABLE IF NOT EXISTS `parse_task`
(
    `id`                BIGINT       NOT NULL AUTO_INCREMENT COMMENT '主键',
    `video_id`          BIGINT       NOT NULL COMMENT '关联 video.id',
    `request_id`        VARCHAR(64)  NOT NULL COMMENT '幂等键（与 MQ 消息一致）',
    `status`            VARCHAR(16)  NOT NULL COMMENT 'PENDING/RUNNING/SUCCESS/FAILED',
    `progress`          INT          NOT NULL DEFAULT 0 COMMENT '进度 0-100',
    `stage`             VARCHAR(32)           DEFAULT NULL COMMENT 'ACQUIRE/TRANSCRIBE/SUMMARIZE/PERSIST',
    `error_message`     TEXT                  DEFAULT NULL COMMENT '失败原因',
    `model`             VARCHAR(128)          DEFAULT NULL COMMENT '使用的模型',
    `mock_ai`           BIT(1)       NOT NULL DEFAULT b'0' COMMENT '是否本地兜底摘要',
    `prompt_tokens`     INT                   DEFAULT NULL,
    `completion_tokens` INT                   DEFAULT NULL,
    `transcript_chars`  INT                   DEFAULT NULL,
    `created_at`        DATETIME(6)  NOT NULL,
    `started_at`        DATETIME(6)           DEFAULT NULL,
    `finished_at`       DATETIME(6)           DEFAULT NULL,
    `duration_ms`       BIGINT                DEFAULT NULL,
    PRIMARY KEY (`id`),
    KEY `idx_task_video_id` (`video_id`),
    KEY `idx_task_status` (`status`),
    KEY `idx_task_request_id` (`request_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='视频解析任务';

-- ===========================================================================
-- 便于排障的视图（可选）
-- ===========================================================================
CREATE OR REPLACE VIEW `v_video_overview` AS
SELECT v.`id`,
       v.`title`,
       v.`source_type`,
       v.`status`,
       v.`file_size`,
       v.`created_at`,
       t.`id`         AS `task_id`,
       t.`status`     AS `task_status`,
       t.`progress`   AS `task_progress`,
       t.`stage`      AS `task_stage`,
       t.`model`,
       t.`mock_ai`,
       t.`duration_ms`
FROM `video` v
         LEFT JOIN `parse_task` t
                   ON t.`id` = (SELECT MAX(t2.`id`) FROM `parse_task` t2 WHERE t2.`video_id` = v.`id`);
