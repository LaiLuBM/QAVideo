package com.qavideo.service;

import com.qavideo.config.AppProperties;
import com.qavideo.domain.Video;
import com.qavideo.service.asr.AsrResult;
import com.qavideo.service.asr.AsrService;
import com.qavideo.service.vision.VisualAnalysisService;
import com.qavideo.storage.StorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Collects the material the agent reasons about.
 *
 * <p>DeepSeek is a text-only model, so a video has to be turned into text first. There are
 * exactly three ways, tried in this order:
 *
 * <ol>
 *   <li><b>Subtitle file</b> next to the media file ({@code clip.mp4} + {@code clip.srt} /
 *       {@code .vtt} / {@code .txt}). This is also where an ASR result is cached.</li>
 *   <li><b>Speech recognition</b> ({@code service/asr}) - for videos where people talk:
 *       courses, interviews, podcasts, meetings.</li>
 *   <li><b>Visual understanding</b> ({@code service/vision}) - for videos where nobody talks
 *       (game highlights, sports, scenery): ffmpeg extracts frames and a vision model
 *       describes what is on screen. Skipped when the audio track already produced enough
 *       text, because that would only cost more tokens.</li>
 * </ol>
 *
 * <p>If none of them produces anything, a clearly-marked placeholder is used that states
 * <em>why</em> - so the LLM can never silently present a fabricated summary as a real one.
 */
@Service
public class TranscriptService {

    private static final Logger log = LoggerFactory.getLogger(TranscriptService.class);
    private static final List<String> SIDECAR_EXTENSIONS = List.of(".srt", ".vtt", ".txt", ".json");
    /** Below this many characters the audio transcript is considered "nothing to summarise". */
    private static final int MIN_USEFUL_AUDIO_CHARS = 30;

    private final StorageService storageService;
    private final AppProperties properties;
    private final AsrService asrService;
    private final VisualAnalysisService visualAnalysisService;

    public TranscriptService(StorageService storageService,
                             AppProperties properties,
                             AsrService asrService,
                             VisualAnalysisService visualAnalysisService) {
        this.storageService = storageService;
        this.properties = properties;
        this.asrService = asrService;
        this.visualAnalysisService = visualAnalysisService;
    }

    /**
     * @return combined material plus the duration probed along the way; {@code text} is
     *         {@code null} only when nothing is available and the placeholder is disabled
     */
    public ContentMaterial extract(Video video) {
        List<String> sections = new ArrayList<>();
        List<String> kinds = new ArrayList<>();
        List<String> reasons = new ArrayList<>();
        Integer duration = video.getDurationSeconds();

        // ---------------------------------------------------------- 1) audio track
        String audio = null;
        if (properties.getTranscript().isSidecarEnabled() && video.getStoragePath() != null) {
            audio = readSidecar(video);
        }
        if (audio == null && video.getStoragePath() != null) {
            AsrAttempt attempt = transcribe(video);
            if (attempt.ok()) {
                audio = attempt.text();
            } else {
                reasons.add(attempt.failureReason());
            }
        } else if (audio == null) {
            reasons.add("该视频没有本地媒体文件（链接导入且未下载直链），无法做语音识别");
        }

        boolean audioIsUseful = audio != null && audio.length() >= MIN_USEFUL_AUDIO_CHARS;

        // ---------------------------------------------------------- 2) visual track
        if (video.getStoragePath() != null && !audioIsUseful) {
            if (visualAnalysisService.enabled()) {
                try {
                    VisualAnalysisService.VisualResult result =
                            visualAnalysisService.analyse(storageService.resolveRelative(video.getStoragePath()));
                    if (result.durationSeconds() != null) {
                        // duration comes from ffprobe and is worth keeping even if the VLM failed
                        duration = result.durationSeconds();
                    }
                    if (result.ok()) {
                        sections.add("【画面描述（视觉模型按时间顺序生成）】\n" + result.description());
                        kinds.add("VISION");
                    } else {
                        reasons.add("画面理解失败：" + result.error());
                    }
                } catch (Exception ex) {
                    log.warn("frame extraction failed for video {}: {}", video.getId(), ex.getMessage());
                    reasons.add("画面理解失败：" + ex.getMessage());
                }
            } else {
                reasons.add(visualAnalysisService.unavailableReason());
            }
        } else if (audioIsUseful) {
            log.debug("video {}: audio transcript has content, skipping frame analysis", video.getId());
        }

        if (audio != null && !audio.isBlank()) {
            sections.add(0, "【语音转写】\n" + audio);
            kinds.add(0, "AUDIO");
        }

        if (!sections.isEmpty()) {
            return new ContentMaterial(String.join("\n\n", sections), String.join("+", kinds), duration);
        }

        // ---------------------------------------------------------- 3) placeholder
        if (properties.getTranscript().isDemoEnabled()) {
            return new ContentMaterial(demoTranscript(video, String.join("；", reasons)), "DEMO", duration);
        }
        return new ContentMaterial(null, "NONE", duration);
    }

    private String readSidecar(Video video) {
        Optional<Path> sidecar = storageService.findSidecarTranscript(video.getStoragePath(), SIDECAR_EXTENSIONS);
        if (sidecar.isEmpty()) {
            return null;
        }
        try {
            String cleaned = clean(Files.readString(sidecar.get(), StandardCharsets.UTF_8));
            if (!cleaned.isBlank()) {
                log.info("video {} transcript loaded from sidecar {}", video.getId(), sidecar.get().getFileName());
                return cleaned;
            }
        } catch (IOException ex) {
            log.warn("cannot read sidecar transcript for video {}: {}", video.getId(), ex.getMessage());
        }
        return null;
    }

    private AsrAttempt transcribe(Video video) {
        if (!asrService.enabled()) {
            return AsrAttempt.failed(asrService.unavailableReason());
        }

        Path media = storageService.resolveRelative(video.getStoragePath());
        long size;
        try {
            size = Files.size(media);
        } catch (IOException ex) {
            return AsrAttempt.failed("无法读取媒体文件: " + ex.getMessage());
        }

        long max = properties.getAsr().getMaxFileSize();
        if (max > 0 && size > max) {
            return AsrAttempt.failed("视频 " + readableSize(size) + " 超过 ASR 上限 " + readableSize(max)
                    + "（provider=" + properties.getAsr().getProvider()
                    + "）。云接口普遍有体积上限，此时请改用本地引擎（qavideo.asr.provider=command）"
                    + "，或先用 ffmpeg 抽取音轨压成 mp3 再上传");
        }

        try {
            Optional<AsrResult> result = asrService.transcribe(media, video.getFileName(), video.getDurationSeconds());
            if (result.isEmpty()) {
                return AsrAttempt.failed(asrService.unavailableReason());
            }
            String cleaned = clean(result.get().text());
            if (cleaned.isBlank()) {
                return AsrAttempt.failed("语音识别返回了空文本");
            }
            storageService.writeSidecarTranscript(video.getStoragePath(), cleaned);
            return AsrAttempt.succeeded(cleaned);
        } catch (Exception ex) {
            log.warn("ASR failed for video {}: {}", video.getId(), ex.getMessage());
            return AsrAttempt.failed("语音识别失败：" + ex.getMessage());
        }
    }

    /** Strips SRT/VTT cue numbers and timecodes, keeping readable prose. */
    String clean(String raw) {
        StringBuilder sb = new StringBuilder();
        for (String line : raw.split("\r?\n")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty()
                    || trimmed.matches("\\d+")
                    || trimmed.contains("-->")
                    || trimmed.equalsIgnoreCase("WEBVTT")
                    || trimmed.startsWith("NOTE ")) {
                continue;
            }
            sb.append(trimmed).append('\n');
        }
        return sb.toString().trim();
    }

    private String demoTranscript(Video video, String reason) {
        String title = video.getTitle() == null ? "未命名视频" : video.getTitle();
        String source = video.getSourceUrl() == null ? ("本地文件 " + video.getFileName()) : video.getSourceUrl();
        String detail = reason == null || reason.isBlank() ? "未启用任何解析方式" : reason;
        return """
                这是一段由 QAVideo 生成的占位文本（不是真实素材，请勿据此判断视频内容）。

                视频标题：%s
                视频来源：%s

                【未能获得真实素材的原因】%s

                让总结基于真实内容，任选一种后重新解析：
                1) 有人讲解的视频 -> 接语音识别（ASR）：
                   云端 QVIDEO_ASR_PROVIDER=openai + QVIDEO_ASR_API_KEY，或本地 QVIDEO_ASR_PROVIDER=command
                2) 没人讲解的视频（游戏集锦/风景/比赛）-> 接画面理解：
                   QVIDEO_VISION_PROVIDER=openai + QVIDEO_VISION_API_KEY + QVIDEO_VISION_MODEL（需要本机安装 ffmpeg）
                3) 或者直接把现成字幕放到媒体文件旁（同名 .srt/.vtt/.txt）

                本段文本仅用于验证「导入 -> 素材提取 -> 大模型总结 -> 入库 -> 前端展示」链路通畅。
                """.formatted(title, source, detail).trim();
    }

    private String readableSize(long bytes) {
        if (bytes < 1024) {
            return bytes + " B";
        }
        if (bytes < 1024 * 1024) {
            return String.format("%.1f KB", bytes / 1024.0);
        }
        if (bytes < 1024L * 1024 * 1024) {
            return String.format("%.1f MB", bytes / (1024.0 * 1024));
        }
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    /**
     * Material handed to the agent.
     *
     * @param kind            which route produced it: AUDIO / VISION / AUDIO+VISION / DEMO / NONE
     * @param durationSeconds duration probed during extraction, may be {@code null}
     */
    public record ContentMaterial(String text, String kind, Integer durationSeconds) {
    }

    /** Outcome of one ASR attempt: either text, or the reason it did not work. */
    private record AsrAttempt(String text, String failureReason) {

        static AsrAttempt succeeded(String text) {
            return new AsrAttempt(text, null);
        }

        static AsrAttempt failed(String reason) {
            return new AsrAttempt(null, reason == null ? "未知原因" : reason);
        }

        boolean ok() {
            return text != null && !text.isBlank();
        }
    }
}
