package com.qavideo.service.ai;

import com.qavideo.common.BizException;
import com.qavideo.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Chooses the engine for one summarization call.
 *
 * <ol>
 *   <li>DeepSeek key configured -&gt; LangChain4j agent.</li>
 *   <li>Call fails and {@code qavideo.ai.mock-when-no-key=true} -&gt; offline fallback,
 *       clearly annotated in the summary text.</li>
 *   <li>No key and fallback disabled -&gt; explicit 400 so misconfiguration is obvious.</li>
 * </ol>
 */
@Service
public class AiSummaryService {

    private static final Logger log = LoggerFactory.getLogger(AiSummaryService.class);

    private final AppProperties properties;
    private final DeepSeekSummaryEngine deepSeekEngine;
    private final MockSummaryEngine mockEngine;

    public AiSummaryService(AppProperties properties,
                            DeepSeekSummaryEngine deepSeekEngine,
                            MockSummaryEngine mockEngine) {
        this.properties = properties;
        this.deepSeekEngine = deepSeekEngine;
        this.mockEngine = mockEngine;
    }

    public SummaryPayload summarize(SummaryRequest request) {
        AppProperties.Ai ai = properties.getAi();
        if (ai.hasApiKey()) {
            try {
                return deepSeekEngine.summarize(request);
            } catch (Exception ex) {
                log.warn("DeepSeek call failed: {}", ex.getMessage());
                if (!ai.isMockWhenNoKey()) {
                    throw BizException.upstream("AI 解析失败: " + ex.getMessage());
                }
                return annotate(mockEngine.summarize(request),
                        "DeepSeek 调用失败（" + ex.getMessage() + "），当前结果由本地兜底摘要生成。");
            }
        }
        if (ai.isMockWhenNoKey()) {
            log.info("DEEPSEEK_API_KEY 未配置，使用本地兜底摘要引擎（mock 模式）");
            return mockEngine.summarize(request);
        }
        throw BizException.badRequest("未配置 DEEPSEEK_API_KEY，且 qavideo.ai.mock-when-no-key=false");
    }

    public boolean apiKeyConfigured() {
        return properties.getAi().hasApiKey();
    }

    /** true when the next call would be served by the offline fallback. */
    public boolean mockModeActive() {
        return !properties.getAi().hasApiKey() && properties.getAi().isMockWhenNoKey();
    }

    public String provider() {
        return properties.getAi().getProvider();
    }

    public String model() {
        return properties.getAi().getModel();
    }

    private SummaryPayload annotate(SummaryPayload payload, String note) {
        String summary = (payload.summary() == null ? "" : payload.summary()) + "\n\n> " + note;
        return new SummaryPayload(
                summary,
                payload.keywords(),
                payload.highlights(),
                payload.chapters(),
                payload.sentiment(),
                payload.targetAudience(),
                payload.provider(),
                payload.model(),
                true,
                payload.promptTokens(),
                payload.completionTokens());
    }
}
