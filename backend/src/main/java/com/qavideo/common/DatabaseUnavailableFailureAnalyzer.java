package com.qavideo.common;

import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.diagnostics.FailureAnalyzer;

/**
 * Turns the raw "unable to obtain JDBC connection" stack trace into an actionable
 * message.
 *
 * <p>Without this, forgetting to start MySQL (or not having Docker at all) produces a
 * wall of Hibernate output that never states the actual problem. This analyzer detects a
 * refused database connection anywhere in the cause chain and prints what to do.
 *
 * <p>Registered in {@code META-INF/spring.factories} under
 * {@code org.springframework.boot.diagnostics.FailureAnalyzer}.
 */
public class DatabaseUnavailableFailureAnalyzer implements FailureAnalyzer {

    @Override
    public FailureAnalysis analyze(Throwable failure) {
        if (!isConnectionRefused(failure)) {
            return null;
        }
        String description = "连不上 MySQL 数据库（Connection refused），应用无法启动。";

        String action = """
                请任选一种方式：

                1) 启动中间件后按「完整中间件」方式运行（需要先安装 Docker Desktop）：
                     docker compose up -d
                   PowerShell 环境变量：
                     $env:QVIDEO_REDIS_ENABLED='true'; $env:QVIDEO_MQ_ENABLED='true'
                     $env:DEEPSEEK_API_KEY='sk-xxx'      # 可选，不填则用本地兜底摘要
                     ./mvnw spring-boot:run

                2) 零依赖体验，不需要 MySQL / Redis / RocketMQ：
                     ./mvnw spring-boot:run -Dspring-boot.run.profiles=standalone   # H2 内存库，重启清空
                     ./mvnw spring-boot:run -Dspring-boot.run.profiles=local-file   # H2 文件库，数据持久化
                   Windows cmd：
                     mvnw.cmd spring-boot:run "-Dspring-boot.run.profiles=local-file"

                3) 已有自己的 MySQL：确认服务已启动，并用环境变量指向它：
                     MYSQL_URL=jdbc:mysql://host:3306/qavideo?useSSL=false&serverTimezone=Asia/Shanghai
                     MYSQL_USER=root
                     MYSQL_PASSWORD=你的密码

                注意：给 spring-boot:run 传应用参数要用 spring-boot.run.* 或上面的 profile，
                直接写 -Dspring.datasource.url=... 只会设置 Maven 自己的系统属性，不会传给应用进程。

                详细说明见项目根目录 README.md。""";

        return new FailureAnalysis(description, action, failure);
    }

    /** Looks for a refused TCP connection attributable to the datasource. */
    private boolean isConnectionRefused(Throwable failure) {
        Throwable current = failure;
        int depth = 0;
        while (current != null && depth++ < 40) {
            String type = current.getClass().getName();
            String message = current.getMessage() == null ? "" : current.getMessage();

            if (type.contains("CommunicationsException")) {
                return true;
            }
            if (message.contains("Communications link failure")) {
                return true;
            }
            if (message.contains("JdbcEnvironment") || message.contains("Unable to determine Dialect")) {
                return true;
            }
            if (type.equals("java.net.ConnectException") && message.contains("Connection refused")) {
                return true;
            }
            current = current.getCause() == current ? null : current.getCause();
        }
        return false;
    }
}
