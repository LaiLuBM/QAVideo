package com.qavideo.service.asr;

/** Transcript produced by an ASR engine. */
public record AsrResult(String text, String language, String provider) {
}
