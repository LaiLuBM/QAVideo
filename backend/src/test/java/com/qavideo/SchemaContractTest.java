package com.qavideo;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Guards the two decisions that fixed the "Data too long for column 'highlights'" bug:
 *
 * <ol>
 *   <li>MySQL LOB columns must be declared {@code LONGTEXT} in the SQL owned by the project,
 *       because Hibernate 6.6's MySQLDialect renders {@code @Lob}/{@code CLOB} Strings as
 *       {@code tinytext} (255 bytes).</li>
 *   <li>Hibernate DDL must stay disabled for MySQL ({@code ddl-auto: none}); with
 *       {@code update} it silently ALTERs the correct {@code longtext} columns back down to
 *       {@code tinytext}.</li>
 * </ol>
 *
 * Regression: parse tasks failed with
 * {@code Data truncation: Data too long for column 'highlights' at row 1}.
 */
class SchemaContractTest {

    private static final List<String> LOB_COLUMNS = List.of("summary", "transcript", "highlights", "chapters");

    @Test
    void applicationSchemaDeclaresWideLobColumns() throws Exception {
        assertLobWidths(readClasspath("db/schema-mysql.sql"), "db/schema-mysql.sql");
    }

    @Test
    void composeBootstrapSchemaDeclaresWideLobColumns() throws Exception {
        Path initSql = Path.of("..", "sql", "init.sql");
        assumeTrue(Files.exists(initSql), "sql/init.sql 只在完整仓库中校验");
        assertLobWidths(Files.readString(initSql, StandardCharsets.UTF_8), "sql/init.sql");
    }

    @Test
    void repairScriptWidensEveryLobColumn() throws Exception {
        Path repair = Path.of("..", "sql", "repair-lob-columns.sql");
        assumeTrue(Files.exists(repair), "sql/repair-lob-columns.sql 只在完整仓库中校验");

        String sql = Files.readString(repair, StandardCharsets.UTF_8).toUpperCase();
        LOB_COLUMNS.forEach(column ->
                assertThat(sql).as("修复脚本必须包含 %s 的 MODIFY", column).contains(column.toUpperCase()));
        assertThat(sql).contains("MODIFY COLUMN");
        assertThat(sql).contains("ERROR_MESSAGE");
    }

    @Test
    void mysqlProfileDoesNotLetHibernateOwnTheSchema() throws Exception {
        String yaml = readClasspath("application.yml");

        assertThat(yaml)
                .as("MySQL 下必须禁用 Hibernate DDL，否则会把 longtext 列改回 tinytext")
                .contains("ddl-auto: none");
        assertThat(yaml)
                .as("MySQL 建表应由 db/schema-mysql.sql 负责")
                .contains("schema-locations: classpath:db/schema-mysql.sql");
    }

    private void assertLobWidths(String sql, String source) {
        LOB_COLUMNS.forEach(column ->
                assertThat(sql)
                        .as("%s：列 `%s` 必须是 LONGTEXT（tinytext 只有 255 字节）", source, column)
                        .containsPattern("`" + column + "`\\s+LONGTEXT"));
        assertThat(sql)
                .as("%s：列 `error_message` 必须是 TEXT 及以上", source)
                .containsPattern("`error_message`\\s+TEXT");
    }

    private String readClasspath(String resource) throws Exception {
        try (InputStream in = getClass().getClassLoader().getResourceAsStream(resource)) {
            assertThat(in).as("classpath 资源 %s 必须存在", resource).isNotNull();
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
