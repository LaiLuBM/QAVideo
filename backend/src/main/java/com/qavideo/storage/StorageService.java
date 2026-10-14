package com.qavideo.storage;

import com.qavideo.common.BizException;
import com.qavideo.config.AppProperties;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Local-disk media storage.
 *
 * <p>Files are content addressed: {@code {root}/yyyy/MM/{sha256}.{ext}}. That gives
 * free deduplication (identical bytes land on the same path), makes the sha256 the
 * natural MySQL dedup key, and keeps directories small.
 */
@Component
public class StorageService {

    private static final Logger log = LoggerFactory.getLogger(StorageService.class);

    private final AppProperties properties;
    private Path root;
    private Path tempRoot;

    public StorageService(AppProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    void prepareDirectories() throws IOException {
        this.root = Paths.get(properties.getStorage().getRoot()).toAbsolutePath().normalize();
        this.tempRoot = Paths.get(properties.getStorage().getTempRoot()).toAbsolutePath().normalize();
        Files.createDirectories(root);
        Files.createDirectories(tempRoot);
        log.info("media storage root={} tempRoot={}", root, tempRoot);
    }

    public Path getRoot() {
        return root;
    }

    public Path getTempRoot() {
        return tempRoot;
    }

    /** Directory holding the chunks of one upload session. */
    public Path uploadDir(String uploadId) throws IOException {
        Path dir = tempRoot.resolve(uploadId).normalize();
        if (!dir.startsWith(tempRoot)) {
            throw BizException.badRequest("非法的上传会话标识");
        }
        Files.createDirectories(dir);
        return dir;
    }

    public Path chunkFile(String uploadId, int index) {
        try {
            return uploadDir(uploadId).resolve(String.format("chunk-%08d.part", index)).normalize();
        } catch (IOException ex) {
            throw BizException.internal("无法创建上传目录: " + ex.getMessage());
        }
    }

    public Path mergedTempFile(String uploadId) {
        try {
            return uploadDir(uploadId).resolve("merged.tmp").normalize();
        } catch (IOException ex) {
            throw BizException.internal("无法创建上传目录: " + ex.getMessage());
        }
    }

    /** Final, content-addressed destination for a merged file. */
    public Path targetPath(String sha256, String extension) {
        LocalDate today = LocalDate.now();
        String dir = String.format("%04d/%02d", today.getYear(), today.getMonthValue());
        return root.resolve(dir).resolve(sha256 + (extension == null ? "" : extension)).normalize();
    }

    public String relativePath(Path file) {
        return root.relativize(file.toAbsolutePath().normalize()).toString().replace('\\', '/');
    }

    public Path resolveRelative(String relative) {
        Path resolved = root.resolve(relative).normalize();
        if (!resolved.startsWith(root)) {
            throw BizException.badRequest("非法的存储路径");
        }
        return resolved;
    }

    public void moveIntoPlace(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ex) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /** Streams a file into {@code target} while computing its sha256. */
    public String copyAndHash(Path source, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(source);
             DigestInputStream digestIn = new DigestInputStream(in, digest);
             OutputStream out = Files.newOutputStream(target)) {
            digestIn.transferTo(out);
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public String sha256(Path file) throws IOException {
        MessageDigest digest = newDigest();
        try (InputStream in = Files.newInputStream(file);
             DigestInputStream digestIn = new DigestInputStream(in, digest)) {
            digestIn.transferTo(OutputStream.nullOutputStream());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    /**
     * Concatenates the chunks of a session in index order and returns the sha256 of the
     * result, so the caller gets the dedup key without a second read pass.
     *
     * @throws IOException when a chunk is missing
     */
    public String mergeChunks(String uploadId, int totalChunks, Path target) throws IOException {
        Files.createDirectories(target.getParent());
        MessageDigest digest = newDigest();
        byte[] buffer = new byte[64 * 1024];
        try (OutputStream out = Files.newOutputStream(target)) {
            for (int index = 0; index < totalChunks; index++) {
                Path chunk = chunkFile(uploadId, index);
                if (!Files.exists(chunk)) {
                    throw new IOException("缺少分片 " + index + "，无法合并");
                }
                try (InputStream in = Files.newInputStream(chunk)) {
                    int read;
                    while ((read = in.read(buffer)) != -1) {
                        digest.update(buffer, 0, read);
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    public Resource asResource(String relativePath) {
        Path path = resolveRelative(relativePath);
        if (!Files.isReadable(path)) {
            throw BizException.notFound("媒体文件不存在或已被清理");
        }
        return new FileSystemResource(path);
    }

    public boolean deleteQuietly(Path path) {
        if (path == null) {
            return false;
        }
        try {
            return Files.deleteIfExists(path);
        } catch (IOException ex) {
            log.warn("cannot delete {}: {}", path, ex.getMessage());
            return false;
        }
    }

    public void deleteRecursively(Path dir) {
        if (dir == null || !Files.exists(dir)) {
            return;
        }
        try (Stream<Path> walk = Files.walk(dir)) {
            List<Path> paths = new ArrayList<>(walk.sorted(Comparator.reverseOrder()).toList());
            for (Path p : paths) {
                deleteQuietly(p);
            }
        } catch (IOException ex) {
            log.warn("cannot clean {}: {}", dir, ex.getMessage());
        }
    }

    /** Lists chunk indexes physically present on disk (used to rebuild lost state). */
    public List<Integer> chunkIndexesOnDisk(String uploadId) {
        Path dir = tempRoot.resolve(uploadId);
        if (!Files.isDirectory(dir)) {
            return List.of();
        }
        try (Stream<Path> files = Files.list(dir)) {
            return files.map(p -> p.getFileName().toString())
                    .filter(name -> name.startsWith("chunk-") && name.endsWith(".part"))
                    .map(name -> name.substring("chunk-".length(), name.length() - ".part".length()))
                    .map(name -> {
                        try {
                            return Integer.parseInt(name);
                        } catch (NumberFormatException ex) {
                            return -1;
                        }
                    })
                    .filter(i -> i >= 0)
                    .sorted()
                    .toList();
        } catch (IOException ex) {
            log.warn("cannot list chunks of {}: {}", uploadId, ex.getMessage());
            return List.of();
        }
    }

    /** Finds a sibling transcript file ({@code .srt/.vtt/.txt}) for a stored media file. */
    public Optional<Path> findSidecarTranscript(String relativeMediaPath, List<String> extensions) {
        Path media = resolveRelative(relativeMediaPath);
        String name = media.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        for (String ext : extensions) {
            Path candidate = media.resolveSibling(base + ext);
            if (Files.isReadable(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    /**
     * Caches an ASR result next to the media file as {@code <name>.txt}.
     *
     * <p>Two benefits: a re-parse does not pay for transcription again, and the user can read
     * (or correct) the transcript on disk. Real {@code .srt}/{@code .vtt} files still win,
     * because {@link #findSidecarTranscript} looks for them first.
     *
     * @return the file that was written, or empty when a transcript already exists
     */
    public Optional<Path> writeSidecarTranscript(String relativeMediaPath, String text) {
        if (text == null || text.isBlank()) {
            return Optional.empty();
        }
        Path media = resolveRelative(relativeMediaPath);
        String name = media.getFileName().toString();
        int dot = name.lastIndexOf('.');
        String base = dot > 0 ? name.substring(0, dot) : name;
        Path target = media.resolveSibling(base + ".txt");
        if (Files.exists(target)) {
            return Optional.empty();
        }
        try {
            Files.writeString(target, text, StandardCharsets.UTF_8);
            log.info("cached transcript at {}", target);
            return Optional.of(target);
        } catch (IOException ex) {
            log.warn("cannot cache transcript at {}: {}", target, ex.getMessage());
            return Optional.empty();
        }
    }

    public static String extensionOf(String fileName) {
        if (fileName == null) {
            return "";
        }
        int dot = fileName.lastIndexOf('.');
        if (dot < 0 || dot == fileName.length() - 1) {
            return "";
        }
        String ext = fileName.substring(dot).toLowerCase(Locale.ROOT);
        return ext.length() > 12 ? "" : ext;
    }

    private MessageDigest newDigest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }
}
