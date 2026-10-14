package com.qavideo.service.ai;

/**
 * Strategy for turning a transcript into a summary. Implemented by
 * {@link DeepSeekSummaryEngine} (LangChain4j) and {@link MockSummaryEngine}
 * (offline extractive fallback).
 */
public interface SummaryEngine {

    SummaryPayload summarize(SummaryRequest request);

    /** Human readable engine name, stored on the parse task. */
    String provider();

    /** true when this engine is the offline fallback rather than a real LLM. */
    boolean mock();
}
