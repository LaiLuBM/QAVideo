package com.qavideo.service.ai;

import dev.langchain4j.service.Result;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * The LangChain4j "chain" for this project.
 *
 * <p>{@code AiServices} turns this interface into a working agent: the system prompt is
 * loaded from {@code prompts/summary-system.txt}, the user message is the rendered video
 * context, and the return type is {@link Result} so token usage can be recorded on the
 * parse task. Swapping DeepSeek for another OpenAI-compatible model is a config change
 * ({@code qavideo.ai.base-url} / {@code qavideo.ai.model}), not a code change.
 */
public interface VideoSummaryAgent {

    @SystemMessage(fromResource = "prompts/summary-system.txt")
    Result<String> analyze(@UserMessage String videoContext);
}
