package com.qavideo.storage;

import com.qavideo.config.AppProperties;
import com.qavideo.lock.DistributedLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * Removes abandoned upload sessions.
 *
 * <p>Redis expires the session <em>metadata</em> by TTL; this job is what reclaims the
 * chunk bytes on disk (and it is the only cleanup the memory store needs).
 */
@Component
public class UploadTempCleaner {

    private static final Logger log = LoggerFactory.getLogger(UploadTempCleaner.class);

    private final StorageService storageService;
    private final UploadStateStore stateStore;
    private final DistributedLock lock;
    private final AppProperties properties;

    public UploadTempCleaner(StorageService storageService,
                             UploadStateStore stateStore,
                             DistributedLock lock,
                             AppProperties properties) {
        this.storageService = storageService;
        this.stateStore = stateStore;
        this.lock = lock;
        this.properties = properties;
    }

    @Scheduled(cron = "${qavideo.upload.cleanup-cron:0 0 * * * *}")
    public void sweep() {
        // in a cluster only one instance should sweep the shared storage
        lock.tryLock("qavideo:lock:upload-cleanup", java.time.Duration.ofMinutes(5)).ifPresent(token -> {
            try {
                doSweep();
            } finally {
                lock.unlock("qavideo:lock:upload-cleanup", token);
            }
        });
    }

    private void doSweep() {
        Instant deadline = Instant.now().minus(properties.getUpload().getSessionTtl());
        stateStore.cleanupExpired(Instant.now());

        Path tempRoot = storageService.getTempRoot();
        if (!Files.isDirectory(tempRoot)) {
            return;
        }
        int removed = 0;
        try (Stream<Path> dirs = Files.list(tempRoot)) {
            for (Path dir : dirs.filter(Files::isDirectory).toList()) {
                Optional<Instant> lastModified = lastModified(dir);
                if (lastModified.isPresent() && lastModified.get().isBefore(deadline)) {
                    storageService.deleteRecursively(dir);
                    stateStore.remove(dir.getFileName().toString());
                    removed++;
                }
            }
        } catch (IOException ex) {
            log.warn("upload cleanup failed: {}", ex.getMessage());
        }
        if (removed > 0) {
            log.info("upload cleanup removed {} stale session(s)", removed);
        }
    }

    private Optional<Instant> lastModified(Path dir) {
        long newest = 0L;
        try (Stream<Path> files = Files.list(dir)) {
            for (Path file : files.toList()) {
                newest = Math.max(newest, Files.getLastModifiedTime(file).toMillis());
            }
        } catch (IOException ex) {
            return Optional.empty();
        }
        if (newest == 0L) {
            try {
                newest = Files.getLastModifiedTime(dir).toMillis();
            } catch (IOException ex) {
                return Optional.empty();
            }
        }
        return Optional.of(Instant.ofEpochMilli(newest));
    }
}
