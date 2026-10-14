package com.qavideo.dto;

import java.util.Map;

/** Diagnostics shown on the front-end "系统状态" card. */
public record SystemStatusDto(
        String application,
        String version,
        String javaVersion,
        boolean redisEnabled,
        boolean redisReachable,
        boolean rocketMqEnabled,
        String rocketMqNameServer,
        String taskTopic,
        String aiProvider,
        String aiModel,
        boolean aiApiKeyConfigured,
        boolean aiMockMode,
        boolean transcriptDemoEnabled,
        long uploadChunkSize,
        long maxUploadSize,
        long videoCount,
        long pendingTaskCount,
        Map<String, Object> extras
) {
}
