package com.qavideo.mq;

/**
 * RocketMQ payload for one parse job. Kept tiny on purpose - the consumer only needs
 * the task id, everything else is read from MySQL.
 */
public record ParseTaskMessage(Long taskId, Long videoId, String requestId, long dispatchedAt) {
}
