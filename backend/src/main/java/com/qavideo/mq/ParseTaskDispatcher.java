package com.qavideo.mq;

/**
 * Hands a parse job to the async execution layer.
 *
 * <p>Two implementations keep the REST layer identical whether or not RocketMQ is
 * deployed: {@link RocketMqParseTaskDispatcher} (production) and
 * {@link LocalParseTaskDispatcher} (standalone / local dev).
 */
public interface ParseTaskDispatcher {

    void dispatch(ParseTaskMessage message);

    /** Human readable transport name, surfaced by /api/system/status. */
    String transport();
}
