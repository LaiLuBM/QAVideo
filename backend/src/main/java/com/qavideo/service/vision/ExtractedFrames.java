package com.qavideo.service.vision;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Frames pulled out of a video, plus the duration learned while doing it.
 *
 * @param frames          JPEG files in chronological order
 * @param workDir         temp directory to delete afterwards
 * @param durationSeconds probed duration, may be {@code null} when ffprobe could not tell
 */
public record ExtractedFrames(List<Path> frames, Path workDir, Integer durationSeconds) {

    public void cleanup() {
        if (workDir == null || !Files.exists(workDir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(workDir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
    }
}
