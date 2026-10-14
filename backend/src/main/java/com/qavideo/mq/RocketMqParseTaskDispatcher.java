package com.qavideo.mq;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.qavideo.config.AppProperties;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Production transport: publish the task to RocketMQ and return.
 *
 * <p>This is what keeps the import API fast under load: uploading a 2 GB video and
 * analysing it with an LLM takes minutes, while the HTTP call should take milliseconds.
 * RocketMQ also gives retries and a durable backlog if the LLM provider is slow or down.
 */
@Component
@ConditionalOnProperty(prefix = "qavideo.mq", name = "enabled", havingValue = "true")
public class RocketMqParseTaskDispatcher implements ParseTaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RocketMqParseTaskDispatcher.class);

    /** Publish attempts before the task is marked failed (the broker may be restarting). */
    private static final int SEND_ATTEMPTS = 3;

    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;
    private final AppProperties properties;

    public RocketMqParseTaskDispatcher(RocketMQTemplate rocketMQTemplate,
                                       ObjectMapper objectMapper,
                                       AppProperties properties) {
        this.rocketMQTemplate = rocketMQTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    public void dispatch(ParseTaskMessage message) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(message);
        } catch (Exception ex) {
            throw new IllegalStateException("解析任务消息序列化失败: " + ex.getMessage(), ex);
        }

        Exception last = null;
        for (int attempt = 1; attempt <= SEND_ATTEMPTS; attempt++) {
            try {
                rocketMQTemplate.syncSend(properties.getMq().getTopic(), payload);
                log.info("dispatch parse task {} to topic {}", message.taskId(), properties.getMq().getTopic());
                return;
            } catch (Exception ex) {
                last = ex;
                log.warn("send of parse task {} failed (attempt {}/{}): {}",
                        message.taskId(), attempt, SEND_ATTEMPTS, ex.getMessage());
                if (attempt < SEND_ATTEMPTS) {
                    try {
                        Thread.sleep(500L * attempt);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        // The task row stays FAILED with an explicit reason instead of silently vanishing;
        // the user can re-trigger it from the UI once the broker is back.
        throw new IllegalStateException("无法投递解析任务到 RocketMQ: "
                + (last == null ? "unknown" : last.getMessage()), last);
    }

    @Override
    public String transport() {
        return "rocketmq";
    }
}
