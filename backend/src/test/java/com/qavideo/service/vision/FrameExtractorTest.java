package com.qavideo.service.vision;

import com.qavideo.config.AppProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Exercises the real ffmpeg/ffprobe calls.
 *
 * <p>Skipped automatically when ffmpeg is not installed, so the build stays green on a bare
 * machine while still proving frame extraction on any machine that has it (the whole feature
 * depends on ffmpeg, so this must not be assumed to work).
 */
class FrameExtractorTest {

    @Test
    void extractsFramesAndProbesDuration(@TempDir Path tempDir) throws Exception {
        assumeTrue(ffmpegAvailable(), "本机未安装 ffmpeg，跳过抽帧测试");

        Path video = tempDir.resolve("clip.mp4");
        generateTestVideo(video);

        AppProperties properties = new AppProperties();
        properties.getVision().setMaxFrames(4);
        properties.getVision().setImageMaxWidth(320);

        FrameExtractor extractor = new FrameExtractor(properties);
        ExtractedFrames extracted = extractor.extract(video);

        try {
            assertThat(extracted.frames()).as("应当抽出画面").isNotEmpty().hasSizeLessThanOrEqualTo(4);
            assertThat(extracted.frames()).allSatisfy(frame -> {
                assertThat(Files.exists(frame)).isTrue();
                try {
                    assertThat(Files.size(frame)).as("每一帧都应是非空 JPEG").isGreaterThan(0L);
                } catch (IOException ex) {
                    throw new AssertionError(ex);
                }
            });
            assertThat(extracted.durationSeconds()).as("ffprobe 应当给出时长").isNotNull().isPositive();
        } finally {
            extracted.cleanup();
            assertThat(Files.exists(extracted.workDir())).as("临时目录应被清理").isFalse();
        }
    }

    @Test
    void reportsAMissingFfmpegClearly(@TempDir Path tempDir) throws Exception {
        Path video = tempDir.resolve("whatever.mp4");
        Files.write(video, "not-a-real-video".getBytes());

        AppProperties properties = new AppProperties();
        properties.getVision().setFfmpegPath("definitely-not-ffmpeg-12345");
        properties.getVision().setFfprobePath("definitely-not-ffprobe-12345");

        FrameExtractor extractor = new FrameExtractor(properties);
        try {
            extractor.extract(video);
            throw new AssertionError("应当因为缺少 ffmpeg 而失败");
        } catch (IllegalStateException ex) {
            assertThat(ex.getMessage()).contains("未找到").contains("ffmpeg");
        }
    }

    private boolean ffmpegAvailable() {
        try {
            Process process = new ProcessBuilder("ffmpeg", "-version")
                    .redirectErrorStream(true)
                    .start();
            process.getInputStream().readAllBytes();
            return process.waitFor(20, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | InterruptedException ex) {
            return false;
        }
    }

    /** Builds a 3 second synthetic clip with ffmpeg itself (testsrc). */
    private void generateTestVideo(Path target) throws Exception {
        List<String> command = List.of("ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc=size=320x240:rate=10:duration=3",
                "-pix_fmt", "yuv420p", target.toAbsolutePath().toString());
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes());
        assertThat(process.waitFor(60, TimeUnit.SECONDS)).isTrue();
        assertThat(process.exitValue()).as("生成测试视频失败: %s", output).isZero();
    }
}
