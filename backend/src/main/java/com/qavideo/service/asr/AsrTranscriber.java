package com.qavideo.service.asr;

/**
 * Speech-to-text strategy.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link OpenAiCompatibleAsrTranscriber} - uploads the media file to an
 *       OpenAI-compatible {@code /audio/transcriptions} endpoint (OpenAI, 硅基流动,
 *       self-hosted faster-whisper-server, ...).</li>
 *   <li>{@link CommandAsrTranscriber} - runs a local CLI such as whisper.cpp /
 *       openai-whisper / faster-whisper.</li>
 * </ul>
 */
public interface AsrTranscriber {

    /** Configuration id, e.g. {@code openai} or {@code command}. */
    String provider();

    AsrResult transcribe(TranscriptionRequest request);
}
