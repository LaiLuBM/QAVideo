package com.qavideo.mq;

import com.qavideo.config.AsyncConfig;
import com.qavideo.service.VideoParsePipeline;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Default transport: run the job on the local worker pool.
 *
 * <p>The HTTP request still returns immediately (the job is queued, not executed inline),
 * which keeps the API responsive; what is missing versus RocketMQ is durability and
 * multi-instance scaling.
 */
@Component
@ConditionalOnProperty(prefix = "qavideo.mq", name = "enabled", havingValue = "false", matchIfMissing = true)
public class LocalParseTaskDispatcher implements ParseTaskDispatcher {

    private static final Logger log = LoggerFactory.getLogger(LocalParseTaskDispatcher.class);

    private final TaskExecutor executor;
    private final VideoParsePipeline pipeline;

    public LocalParseTaskDispatcher(@Qualifier(AsyncConfig.PARSE_EXECUTOR) TaskExecutor executor,
                                    VideoParsePipeline pipeline) {
        this.executor = executor;
        this.pipeline = pipeline;
    }

    @Override
    public void dispatch(ParseTaskMessage message) {
        log.info("dispatch parse task {} locally (requestId={})", message.taskId(), message.requestId());
        executor.execute(() -> pipeline.execute(message.taskId()));
    }

    @Override
    public String transport() {
        return "local-executor";
    }
}
