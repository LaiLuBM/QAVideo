package com.qavideo.service.asr;

import java.nio.file.Path;

/**
 * One speech-to-text job.
 *
 * @param mediaFile may be a video <em>or</em> an audio file - ASR engines demux the audio
 *                  track themselves, so the caller never has to split the container first
 */
public record TranscriptionRequest(Path mediaFile, String fileName, Integer durationSeconds) {
}
