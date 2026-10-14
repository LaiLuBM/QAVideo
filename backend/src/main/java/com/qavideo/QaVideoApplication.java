package com.qavideo;

import com.qavideo.config.AppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * QAVideo - AI video parsing & summarization platform.
 *
 * <p>Request path: Vue front-end -> REST API -> MySQL (metadata/dedup) + object storage (local disk)
 * -> RocketMQ (async parse job) -> LangChain4j agent (DeepSeek) -> summary persisted back to MySQL.
 */
@SpringBootApplication
@EnableAsync
@EnableScheduling
@EnableConfigurationProperties(AppProperties.class)
public class QaVideoApplication {

    public static void main(String[] args) {
        SpringApplication.run(QaVideoApplication.class, args);
    }
}
