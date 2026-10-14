package com.qavideo.service.asr;

import com.qavideo.config.AppProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.stream.Stream;

/**
 * Runs a local ASR CLI (whisper.cpp, openai-whisper, faster-whisper, ...).
 *
 * <p>Local engines accept the <b>video file directly</b> - they shell out to ffmpeg
 * internally to demux the audio - so no manual extraction step is required.
 *
 * <p>Command template placeholders: {@code {file}}, {@code {outDir}}, {@code {language}}.
 * Example:
 * <pre>
 * qavideo.asr.command=whisper --model small --language {language} --output_format srt --output_dir {outDir} {file}
 * </pre>
 * The command must write a {@code .srt} / {@code .vtt} / {@code .txt} file into
 * {@code {outDir}}.
 */
@Component
public class CommandAsrTranscriber implements AsrTranscriber {

    private static final Logger log = LoggerFactory.getLogger(CommandAsrTranscriber.class);
    private static final List<String> TRANSCRIPT_EXTENSIONS = List.of(".srt", ".vtt", ".txt");

    private final AppProperties properties;

    public CommandAsrTranscriber(AppProperties properties) {
        this.properties = properties;
    }

    @Override
    public String provider() {
        return "command";
    }

    @Override
    public AsrResult transcribe(TranscriptionRequest request) {
        AppProperties.Asr asr = properties.getAsr();
        String template = asr.getCommand();
        if (template == null || template.isBlank()) {
            throw new IllegalStateException("ASR provider=command 但未配置 qavideo.asr.command");
        }

        Path outDir;
        try {
            outDir = Files.createTempDirectory("qavideo-asr-");
        } catch (IOException ex) {
            throw new IllegalStateException("无法创建 ASR 临时目录: " + ex.getMessage(), ex);
        }

        String language = asr.getLanguage() == null ? "zh" : asr.getLanguage();
        String command = template
                .replace("{file}", quote(request.mediaFile().toAbsolutePath().toString()))
                .replace("{outDir}", quote(outDir.toAbsolutePath().toString()))
                .replace("{language}", language);

        log.info("ASR command: {}", command);

        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        ProcessBuilder builder = windows
                ? new ProcessBuilder("cmd.exe", "/c", command)
                : new ProcessBuilder("sh", "-c", command);
        builder.redirectErrorStream(true);
        Path logFile = outDir.resolve("asr-command.log");
        builder.redirectOutput(logFile.toFile());

        Duration timeout = asr.getTimeout() == null ? Duration.ofMinutes(10) : asr.getTimeout();
        try {
            Process process = builder.start();
            if (!process.waitFor(timeout.toSeconds(), java.util.concurrent.TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IllegalStateException("ASR 命令超时（" + timeout.toMinutes() + " 分钟），临时目录保留在 " + outDir);
            }
            if (process.exitValue() != 0) {
                throw new IllegalStateException("ASR 命令退出码 " + process.exitValue()
                        + "，日志：" + readTail(logFile));
            }

            Path transcript = pickTranscript(outDir, asr.getOutputFormat());
            if (transcript == null) {
                throw new IllegalStateException("ASR 命令执行成功，但 " + outDir
                        + " 里没有 .srt/.vtt/.txt。请确认命令把字幕写到了 {outDir}");
            }
            String text = Files.readString(transcript, StandardCharsets.UTF_8);
            log.info("ASR done: provider=command chars={} from {}", text.length(), transcript.getFileName());
            deleteRecursively(outDir);
            return new AsrResult(text, language, provider());
        } catch (IOException ex) {
            throw new IllegalStateException("ASR 命令执行失败: " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("ASR 命令被中断", ex);
        }
    }

    private Path pickTranscript(Path outDir, String preferredFormat) throws IOException {
        List<Path> candidates;
        try (Stream<Path> files = Files.list(outDir)) {
            candidates = files.filter(Files::isRegularFile)
                    .filter(path -> TRANSCRIPT_EXTENSIONS.stream()
                            .anyMatch(ext -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(ext)))
                    .sorted(Comparator.comparing(Path::toString))
                    .toList();
        }
        if (candidates.isEmpty()) {
            return null;
        }
        if (preferredFormat != null && !preferredFormat.isBlank()) {
            String suffix = "." + preferredFormat.toLowerCase(Locale.ROOT).replace(".", "");
            for (Path candidate : candidates) {
                if (candidate.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(suffix)) {
                    return candidate;
                }
            }
        }
        return candidates.get(0);
    }

    private String readTail(Path logFile) {
        try {
            if (!Files.exists(logFile)) {
                return "(无日志)";
            }
            String content = Files.readString(logFile, StandardCharsets.UTF_8);
            return content.length() <= 500 ? content : content.substring(content.length() - 500);
        } catch (IOException ex) {
            return "(日志不可读: " + ex.getMessage() + ")";
        }
    }

    private String quote(String value) {
        return "\"" + value + "\"";
    }

    private void deleteRecursively(Path dir) {
        try (Stream<Path> walk = Files.walk(dir)) {
            walk.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort cleanup
                }
            });
        } catch (IOException ex) {
            log.debug("cannot clean ASR temp dir {}: {}", dir, ex.getMessage());
        }
    }
}
