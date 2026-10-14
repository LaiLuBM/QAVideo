package com.qavideo.service.asr;

import com.qavideo.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Picks the configured {@link AsrTranscriber} and reports why ASR is unavailable when it is.
 *
 * <p>Unavailable is not an error: the pipeline falls back to a sidecar file or the demo
 * transcript, and the reason is written into that placeholder text so the user sees it in
 * the summary instead of silently getting a fake one.
 */
@Service
public class AsrService {

    private static final Logger log = LoggerFactory.getLogger(AsrService.class);

    private final AppProperties properties;
    private final Map<String, AsrTranscriber> transcriberByProvider = new HashMap<>();
    private String unavailableReason;

    public AsrService(AppProperties properties, List<AsrTranscriber> transcribers) {
        this.properties = properties;
        transcribers.forEach(transcriber ->
                transcriberByProvider.put(transcriber.provider().toLowerCase(Locale.ROOT), transcriber));
    }

    @PostConstruct
    void logConfiguration() {
        AppProperties.Asr asr = properties.getAsr();
        if (!asr.isEnabled()) {
            unavailableReason = "未启用语音识别（qavideo.asr.provider=none）";
            log.info("ASR disabled (provider=none); 视频将使用字幕文件或占位文本");
            return;
        }
        Optional<AsrTranscriber> active = resolve();
        if (active.isEmpty()) {
            log.warn("ASR provider={} 不可用：{}", asr.getProvider(), unavailableReason);
            return;
        }
        log.info("ASR enabled: provider={} model={} language={}",
                asr.getProvider(), asr.getModel(), asr.getLanguage());
    }

    public boolean enabled() {
        return resolve().isPresent();
    }

    /** Reason ASR is not running, for logs and for the placeholder transcript. */
    public String unavailableReason() {
        resolve();
        return unavailableReason;
    }

    public Optional<AsrResult> transcribe(Path mediaFile, String fileName, Integer durationSeconds) {
        return resolve().map(transcriber ->
                transcriber.transcribe(new TranscriptionRequest(mediaFile, fileName, durationSeconds)));
    }

    private Optional<AsrTranscriber> resolve() {
        AppProperties.Asr asr = properties.getAsr();
        unavailableReason = null;
        if (!asr.isEnabled()) {
            unavailableReason = "未启用语音识别（qavideo.asr.provider=none）";
            return Optional.empty();
        }
        String key = asr.getProvider().toLowerCase(Locale.ROOT);
        AsrTranscriber transcriber = transcriberByProvider.get(key);
        if (transcriber == null) {
            unavailableReason = "未知的 asr.provider=" + asr.getProvider()
                    + "（可选：" + String.join(" / ", transcriberByProvider.keySet()) + "）";
            return Optional.empty();
        }
        if ("openai".equals(key) && !asr.hasApiKey()) {
            unavailableReason = "provider=openai 但未配置 QVIDEO_ASR_API_KEY";
            return Optional.empty();
        }
        if ("command".equals(key) && (asr.getCommand() == null || asr.getCommand().isBlank())) {
            unavailableReason = "provider=command 但未配置 qavideo.asr.command";
            return Optional.empty();
        }
        return Optional.of(transcriber);
    }
}
