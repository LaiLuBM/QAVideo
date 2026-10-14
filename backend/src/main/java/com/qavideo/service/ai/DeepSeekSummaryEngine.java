package com.qavideo.service.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.qavideo.config.AppProperties;
import com.qavideo.dto.ChapterDto;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import dev.langchain4j.service.Result;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * DeepSeek engine, driven through LangChain4j.
 *
 * <p>DeepSeek exposes an OpenAI-compatible endpoint, so the LangChain4j OpenAI client is
 * reused with {@code base-url=https://api.deepseek.com/v1}. The API key field is
 * intentionally a placeholder in configuration ({@code DEEPSEEK_API_KEY}); when it is
 * empty this engine is never invoked and {@link MockSummaryEngine} takes over.
 */
@Component
public class DeepSeekSummaryEngine implements SummaryEngine {

    private static final Logger log = LoggerFactory.getLogger(DeepSeekSummaryEngine.class);

    private final AppProperties properties;
    private final ObjectMapper objectMapper;
    private VideoSummaryAgent agent;

    public DeepSeekSummaryEngine(AppProperties properties, ObjectMapper objectMapper) {
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    void buildAgent() {
        AppProperties.Ai ai = properties.getAi();
        ChatModel model = OpenAiChatModel.builder()
                .baseUrl(ai.getBaseUrl())
                .apiKey(ai.hasApiKey() ? ai.getApiKey() : "not-configured")
                .modelName(ai.getModel())
                .temperature(ai.getTemperature())
                .timeout(ai.getTimeout())
                .maxRetries(ai.getMaxRetries())
                .logRequests(ai.isLogRequests())
                .logResponses(ai.isLogResponses())
                .build();

        this.agent = AiServices.builder(VideoSummaryAgent.class)
                .chatModel(model)
                .build();

        log.info("LangChain4j agent ready: provider={} model={} baseUrl={} apiKeyConfigured={}",
                ai.getProvider(), ai.getModel(), ai.getBaseUrl(), ai.hasApiKey());
    }

    @Override
    public SummaryPayload summarize(SummaryRequest request) {
        String context = buildContext(request);
        long started = System.currentTimeMillis();
        Result<String> result = agent.analyze(context);
        String raw = result == null ? null : result.content();
        log.debug("DeepSeek answered in {} ms", System.currentTimeMillis() - started);

        String json = extractJson(raw);
        if (json == null) {
            throw new IllegalStateException("模型未返回可解析的 JSON: " + abbreviate(raw));
        }

        SummaryPayload payload = parse(json);
        Integer promptTokens = null;
        Integer completionTokens = null;
        if (result != null && result.tokenUsage() != null) {
            promptTokens = result.tokenUsage().inputTokenCount();
            completionTokens = result.tokenUsage().outputTokenCount();
        }
        return new SummaryPayload(
                payload.summary(),
                payload.keywords(),
                payload.highlights(),
                payload.chapters(),
                payload.sentiment(),
                payload.targetAudience(),
                provider(),
                properties.getAi().getModel(),
                false,
                promptTokens,
                completionTokens);
    }

    @Override
    public String provider() {
        return properties.getAi().getProvider();
    }

    @Override
    public boolean mock() {
        return false;
    }

    /** Renders the video context that becomes the user message. */
    private String buildContext(SummaryRequest request) {
        int limit = properties.getAi().getMaxTranscriptChars();
        String transcript = request.transcript() == null ? "" : request.transcript().trim();
        boolean truncated = transcript.length() > limit;
        if (truncated) {
            transcript = transcript.substring(0, limit);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("视频标题：").append(blankTo(request.title(), "（未提供）")).append('\n');
        sb.append("来源链接：").append(blankTo(request.sourceUrl(), "（本地文件）")).append('\n');
        sb.append("视频时长：").append(formatDuration(request.durationSeconds())).append('\n');
        sb.append("视频素材").append(truncated ? "（已截断）" : "").append("：\n");
        sb.append(transcript.isEmpty() ? "（没有提取到任何素材）" : transcript);
        return sb.toString();
    }

    private SummaryPayload parse(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            List<String> keywords = readStringArray(node.get("keywords"));
            List<String> highlights = readStringArray(node.get("highlights"));
            List<ChapterDto> chapters = new ArrayList<>();
            JsonNode chapterNode = node.get("chapters");
            if (chapterNode != null && chapterNode.isArray()) {
                for (JsonNode item : chapterNode) {
                    chapters.add(new ChapterDto(
                            text(item, "start"),
                            text(item, "title"),
                            text(item, "summary")));
                }
            }
            return new SummaryPayload(
                    text(node, "summary"),
                    keywords,
                    highlights,
                    chapters,
                    text(node, "sentiment"),
                    text(node, "targetAudience"),
                    provider(),
                    properties.getAi().getModel(),
                    false,
                    null,
                    null);
        } catch (Exception ex) {
            throw new IllegalStateException("解析模型 JSON 失败: " + ex.getMessage(), ex);
        }
    }

    /** Tolerates ```json fences and any prose wrapped around the object. */
    static String extractJson(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        int fence = text.indexOf("```");
        if (fence >= 0) {
            int end = text.indexOf("```", fence + 3);
            text = end > 0 ? text.substring(fence + 3, end) : text.substring(fence + 3);
            if (text.startsWith("json")) {
                text = text.substring(4);
            }
            text = text.trim();
        }
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        return start >= 0 && end > start ? text.substring(start, end + 1) : null;
    }

    private List<String> readStringArray(JsonNode node) {
        List<String> values = new ArrayList<>();
        if (node != null && node.isArray()) {
            for (JsonNode item : node) {
                if (item.isTextual() && !item.asText().isBlank()) {
                    values.add(item.asText().trim());
                }
            }
        }
        return values;
    }

    private String text(JsonNode node, String field) {
        JsonNode value = node == null ? null : node.get(field);
        return value == null || value.isNull() ? null : value.asText().trim();
    }

    private static String formatDuration(Integer seconds) {
        if (seconds == null || seconds <= 0) {
            return "未知";
        }
        return String.format("%d 分 %02d 秒", seconds / 60, seconds % 60);
    }

    private static String blankTo(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= 200 ? value : value.substring(0, 200) + "...";
    }
}
