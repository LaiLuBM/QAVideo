-- ---------------------------------------------------------------------------
-- 修复脚本：把被 Hibernate 改小的 LOB 列改回 LONGTEXT / TEXT
--
-- 背景：Hibernate 6.6 的 MySQLDialect 把 @Lob/CLOB 的 String 渲染成 tinytext（255 字节），
--       并且 ddl-auto=update 会 ALTER 已有列去“对齐”它自己的期望，于是
--       summary / transcript / highlights / chapters / error_message 被改成了 tinytext，
--       解析任务随之报 “Data truncation: Data too long for column 'highlights'”。
--
-- 用法（二选一）：
--   docker exec -i qavideo-mysql mysql -uroot -proot qavideo < sql/repair-lob-columns.sql
--   mysql -uroot -p qavideo < sql/repair-lob-columns.sql
--
-- 该脚本只加宽列，不会删除或改写任何数据，可重复执行。
-- 升级代码后 Hibernate 已设为 ddl-auto=none，不会再把列改小。
-- ---------------------------------------------------------------------------

ALTER TABLE `video`
    MODIFY COLUMN `summary`    LONGTEXT NULL COMMENT 'AI 总结（必须 LONGTEXT）',
    MODIFY COLUMN `transcript` LONGTEXT NULL COMMENT '字幕/转写文本（必须 LONGTEXT）',
    MODIFY COLUMN `highlights` LONGTEXT NULL COMMENT '要点 JSON 数组（必须 LONGTEXT）',
    MODIFY COLUMN `chapters`   LONGTEXT NULL COMMENT '章节 JSON 数组（必须 LONGTEXT）';

ALTER TABLE `parse_task`
    MODIFY COLUMN `error_message` TEXT NULL COMMENT '失败原因（必须 TEXT 及以上）';

-- 验证：两个查询都应返回 longtext / text，而不是 tinytext
SELECT TABLE_NAME, COLUMN_NAME, COLUMN_TYPE
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = DATABASE()
  AND COLUMN_NAME IN ('summary', 'transcript', 'highlights', 'chapters', 'error_message')
ORDER BY TABLE_NAME, COLUMN_NAME;
