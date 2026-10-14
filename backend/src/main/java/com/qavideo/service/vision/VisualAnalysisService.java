package com.qavideo.service.vision;

import com.qavideo.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Turns a video into a written description of what happens on screen.
 *
 * <p>This is the route for videos with no speech (game highlights, sports, scenery): there is
 * nothing for ASR to transcribe and DeepSeek cannot watch the video, so frames are extracted
 * and described by a vision model first.
 */
@Service
public class VisualAnalysisService {

    private static final Logger log = LoggerFactory.getLogger(VisualAnalysisService.class);

    private static final String DEFAULT_PROMPT = """
            这些是同一个视频里按时间顺序抽取的画面。请用简体中文逐一描述每张画面：
            出现了什么场景、人物或物体、正在发生什么动作、画面上有什么文字（如分数、血量、UI）。
            只描述你真实看到的内容，看不清就写“画面不清晰”，不要推测剧情或不存在的信息。
            输出格式（每行一条，编号与给定顺序一致）：
            画面1：...
            画面2：...
            """;

    private final AppProperties properties;
    private final FrameExtractor frameExtractor;
    private final VisionModelClient visionModelClient;

    public VisualAnalysisService(AppProperties properties,
                                 FrameExtractor frameExtractor,
                                 VisionModelClient visionModelClient) {
        this.properties = properties;
        this.frameExtractor = frameExtractor;
        this.visionModelClient = visionModelClient;
    }

    @PostConstruct
    void logConfiguration() {
        if (enabled()) {
            log.info("视觉理解已启用: provider={} model={} maxFrames={}",
                    properties.getVision().getProvider(),
                    properties.getVision().getModel(),
                    properties.getVision().getMaxFrames());
        } else {
            log.info("视觉理解未启用（qavideo.vision.provider=none）：{}", unavailableReason());
        }
    }

    public boolean enabled() {
        AppProperties.Vision vision = properties.getVision();
        if (!vision.isEnabled()) {
            return false;
        }
        // only the openai-compatible provider ships today
        return "openai".equalsIgnoreCase(vision.getProvider()) && vision.hasApiKey();
    }

    public String unavailableReason() {
        AppProperties.Vision vision = properties.getVision();
        if (!vision.isEnabled()) {
            return "未启用画面理解（qavideo.vision.provider=none）";
        }
        if (!"openai".equalsIgnoreCase(vision.getProvider())) {
            return "未知的 vision.provider=" + vision.getProvider() + "（可选：openai）";
        }
        if (!vision.hasApiKey()) {
            return "provider=openai 但未配置 QVIDEO_VISION_API_KEY";
        }
        return null;
    }

    /**
     * Describes what is on screen.
     *
     * <p>Per-frame failures are reported in the result instead of thrown, so the caller can
     * still use the duration probed on the way - and so one flaky vision call cannot fail
     * the whole parse task.
     *
     * @throws IllegalStateException only when frames could not be extracted at all
     *                               (ffmpeg missing, unreadable video, ...)
     */
    public VisualResult analyse(Path video) {
        if (!enabled()) {
            return new VisualResult(null, unavailableReason(), null);
        }

        ExtractedFrames extracted = frameExtractor.extract(video);
        Integer durationSeconds = extracted.durationSeconds();
        try {
            String prompt = properties.getVision().getPrompt() == null || properties.getVision().getPrompt().isBlank()
                    ? DEFAULT_PROMPT
                    : properties.getVision().getPrompt();

            int batchSize = Math.max(1, properties.getVision().getBatchSize());
            List<String> sections = new ArrayList<>();
            List<Path> frames = extracted.frames();
            for (int start = 0; start < frames.size(); start += batchSize) {
                List<Path> batch = frames.subList(start, Math.min(frames.size(), start + batchSize));
                String described = visionModelClient.describe(batch, prompt);
                if (described != null && !described.isBlank()) {
                    sections.add(described.trim());
                }
            }
            if (sections.isEmpty()) {
                return new VisualResult(null, "视觉模型没有返回任何画面描述", durationSeconds);
            }
            return new VisualResult(String.join("\n", sections), null, durationSeconds);
        } catch (Exception ex) {
            log.warn("vision model call failed: {}", ex.getMessage());
            return new VisualResult(null, ex.getMessage(), durationSeconds);
        } finally {
            extracted.cleanup();
        }
    }

    /**
     * Outcome of one visual analysis attempt.
     *
     * @param description     what the vision model saw, {@code null} when it failed
     * @param error           reason it failed, {@code null} on success
     * @param durationSeconds duration probed while extracting frames, independent of success
     */
    public record VisualResult(String description, String error, Integer durationSeconds) {

        public boolean ok() {
            return description != null && !description.isBlank();
        }
    }
}
