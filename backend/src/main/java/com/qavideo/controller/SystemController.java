package com.qavideo.controller;

import com.qavideo.common.ApiResponse;
import com.qavideo.config.AppProperties;
import com.qavideo.domain.enums.TaskStatus;
import com.qavideo.dto.SystemStatusDto;
import com.qavideo.mq.ParseTaskDispatcher;
import com.qavideo.mq.RocketMqParseTaskConsumer;
import com.qavideo.repository.ParseTaskRepository;
import com.qavideo.repository.VideoRepository;
import com.qavideo.service.VideoService;
import com.qavideo.service.ai.AiSummaryService;
import com.qavideo.service.asr.AsrService;
import com.qavideo.service.vision.VisualAnalysisService;
import com.qavideo.storage.UploadStateStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Diagnostics for the front-end banner: which middleware is actually live, and which
 * AI engine will serve the next request.
 */
@RestController
@RequestMapping("/api/system")
public class SystemController {

    private static final String VERSION = "1.0.0";

    private final AppProperties properties;
    private final AiSummaryService aiSummaryService;
    private final ParseTaskDispatcher dispatcher;
    private final UploadStateStore uploadStateStore;
    private final AsrService asrService;
    private final VisualAnalysisService visualAnalysisService;
    private final ObjectProvider<StringRedisTemplate> redisTemplateProvider;
    private final ObjectProvider<RocketMqParseTaskConsumer> mqConsumer;
    private final VideoRepository videoRepository;
    private final ParseTaskRepository taskRepository;
    private final VideoService videoService;

    @Value("${rocketmq.name-server:}")
    private String rocketMqNameServer;

    public SystemController(AppProperties properties,
                            AiSummaryService aiSummaryService,
                            ParseTaskDispatcher dispatcher,
                            UploadStateStore uploadStateStore,
                            AsrService asrService,
                            VisualAnalysisService visualAnalysisService,
                            ObjectProvider<StringRedisTemplate> redisTemplateProvider,
                            ObjectProvider<RocketMqParseTaskConsumer> mqConsumer,
                            VideoRepository videoRepository,
                            ParseTaskRepository taskRepository,
                            VideoService videoService) {
        this.properties = properties;
        this.aiSummaryService = aiSummaryService;
        this.dispatcher = dispatcher;
        this.uploadStateStore = uploadStateStore;
        this.asrService = asrService;
        this.visualAnalysisService = visualAnalysisService;
        this.redisTemplateProvider = redisTemplateProvider;
        this.mqConsumer = mqConsumer;
        this.videoRepository = videoRepository;
        this.taskRepository = taskRepository;
        this.videoService = videoService;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("application", "qavideo");
        body.put("version", VERSION);
        return ApiResponse.ok(body);
    }

    @GetMapping("/status")
    public ApiResponse<SystemStatusDto> status() {
        boolean redisEnabled = properties.getRedis().isEnabled();
        RocketMqParseTaskConsumer consumer = mqConsumer.getIfAvailable();
        Map<String, Object> extras = new LinkedHashMap<>();
        extras.put("mqTransport", dispatcher.transport());
        extras.put("mqConsumerConnected", consumer != null && consumer.isConnected());
        extras.put("asrProvider", asrService.enabled() ? properties.getAsr().getProvider() : "none");
        extras.put("asrUnavailableReason", asrService.enabled() ? null : asrService.unavailableReason());
        extras.put("visionProvider", visualAnalysisService.enabled() ? properties.getVision().getProvider() : "none");
        extras.put("visionUnavailableReason",
                visualAnalysisService.enabled() ? null : visualAnalysisService.unavailableReason());
        extras.put("uploadStateStore", uploadStateStore.isDistributed() ? "redis" : "memory");
        extras.put("maxTranscriptChars", properties.getAi().getMaxTranscriptChars());
        extras.put("mediaDownloadEnabled", properties.getMedia().isDownloadEnabled());

        SystemStatusDto dto = new SystemStatusDto(
                "qavideo",
                VERSION,
                System.getProperty("java.version"),
                redisEnabled,
                redisEnabled && pingRedis(),
                properties.getMq().isEnabled(),
                rocketMqNameServer,
                properties.getMq().getTopic(),
                aiSummaryService.provider(),
                aiSummaryService.model(),
                aiSummaryService.apiKeyConfigured(),
                aiSummaryService.mockModeActive(),
                properties.getTranscript().isDemoEnabled(),
                properties.getUpload().getChunkSize(),
                properties.getUpload().getMaxFileSize(),
                videoService.count(),
                taskRepository.countByStatus(TaskStatus.PENDING) + taskRepository.countByStatus(TaskStatus.RUNNING),
                extras);
        return ApiResponse.ok(dto);
    }

    private boolean pingRedis() {
        StringRedisTemplate template = redisTemplateProvider.getIfAvailable();
        if (template == null) {
            return false;
        }
        try {
            String pong = template.execute((RedisCallback<String>) RedisConnection::ping);
            return pong != null && pong.equalsIgnoreCase("PONG");
        } catch (Exception ex) {
            return false;
        }
    }

    /** Kept for the front-end footer counters. */
    @GetMapping("/ping")
    public ApiResponse<Map<String, Object>> ping() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("videos", videoRepository.count());
        body.put("pendingTasks", taskRepository.countByStatus(TaskStatus.PENDING));
        return ApiResponse.ok(body);
    }
}
