package com.qavideo.service.vision;

import com.qavideo.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Pulls a handful of frames out of a video with ffmpeg/ffprobe.
 *
 * <p>Frames are spread over the whole video (one every {@code duration / maxFrames} seconds)
 * and downscaled, because every frame becomes one image in a vision-model request.
 *
 * <p>Also probes the duration, which conveniently fills the {@code duration_seconds} column
 * that would otherwise stay empty.
 */
@Component
public class FrameExtractor {

    private static final Logger log = LoggerFactory.getLogger(FrameExtractor.class);

    private final AppProperties properties;

    public FrameExtractor(AppProperties properties) {
        this.properties = properties;
    }

    public ExtractedFrames extract(Path video) {
        AppProperties.Vision vision = properties.getVision();
        if (!Files.isReadable(video)) {
            throw new IllegalStateException("视频文件不存在或不可读: " + video);
        }

        Integer durationSeconds = probeDurationSeconds(video);
        int maxFrames = Math.max(1, vision.getMaxFrames());
        int interval = vision.getFrameIntervalSeconds();
        if (interval <= 0) {
            interval = durationSeconds != null && durationSeconds > 0
                    ? Math.max(1, durationSeconds / maxFrames)
                    : 10;
        }

        Path workDir;
        try {
            workDir = Files.createTempDirectory("qavideo-frames-");
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建抽帧临时目录: " + ex.getMessage(), ex);
        }

        String filter = String.format("fps=1/%d,scale=%d:-2", interval, Math.max(160, vision.getImageMaxWidth()));
        List<String> command = List.of(
                vision.getFfmpegPath(), "-hide_banner", "-loglevel", "error", "-y",
                "-i", video.toAbsolutePath().toString(),
                "-vf", filter,
                "-frames:v", String.valueOf(maxFrames),
                "-q:v", "4",
                workDir.resolve("frame_%03d.jpg").toAbsolutePath().toString());

        log.info("extracting frames: interval={}s maxFrames={} from {}", interval, maxFrames, video.getFileName());
        run(command, "ffmpeg", "抽帧", vision);

        List<Path> frames = listFrames(workDir);
        if (frames.isEmpty()) {
            cleanup(workDir);
            throw new IllegalStateException("ffmpeg 没有抽出任何画面（视频可能损坏或不是有效视频流）");
        }
        log.info("extracted {} frame(s), probed duration={}s", frames.size(), durationSeconds);
        return new ExtractedFrames(frames, workDir, durationSeconds);
    }

    /** @return duration in whole seconds, or {@code null} when ffprobe cannot determine it */
    public Integer probeDurationSeconds(Path video) {
        AppProperties.Vision vision = properties.getVision();
        String output;
        try {
            output = runCapture(List.of(
                    vision.getFfprobePath(), "-v", "error",
                    "-show_entries", "format=duration",
                    "-of", "default=nw=1:nk=1",
                    video.toAbsolutePath().toString()), vision);
        } catch (RuntimeException ex) {
            log.debug("ffprobe failed for {}: {}", video.getFileName(), ex.getMessage());
            return null;
        }
        try {
            double seconds = Double.parseDouble(output.trim());
            return seconds > 0 ? (int) Math.round(seconds) : null;
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private List<Path> listFrames(Path workDir) {
        try (Stream<Path> files = Files.list(workDir)) {
            List<Path> frames = new ArrayList<>(files
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jpg"))
                    .toList());
            frames.sort(Comparator.comparing(path -> path.getFileName().toString()));
            return frames;
        } catch (IOException ex) {
            return List.of();
        }
    }

    private void run(List<String> command, String tool, String action, AppProperties.Vision vision) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(vision.getTimeout().toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException(tool + " " + action + "超时（" + vision.getTimeout().toMinutes() + " 分钟）");
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException(tool + " " + action + "失败（退出码 " + process.exitValue() + "）："
                        + abbreviate(output));
            }
        } catch (IOException ex) {
            throw new IllegalStateException(missingToolMessage(tool) + "：" + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(tool + " " + action + "被中断", ex);
        }
    }

    private String runCapture(List<String> command, AppProperties.Vision vision) {
        try {
            Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
            String output;
            try (InputStream in = process.getInputStream()) {
                output = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            if (!process.waitFor(vision.getTimeout().toSeconds(), TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("ffprobe 超时");
            }
            return output;
        } catch (IOException ex) {
            throw new IllegalStateException(missingToolMessage("ffprobe") + "：" + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ffprobe 被中断", ex);
        }
    }

    private String missingToolMessage(String tool) {
        return "未找到 " + tool + "，画面理解需要先安装 ffmpeg 并加入 PATH"
                + "（Windows: winget install Gyan.FFmpeg；或设置 qavideo.vision." + tool + "-path 指向可执行文件）";
    }

    private void cleanup(Path workDir) {
        new ExtractedFrames(List.of(), workDir, null).cleanup();
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        return trimmed.length() <= 300 ? trimmed : trimmed.substring(0, 300) + "...";
    }
}
