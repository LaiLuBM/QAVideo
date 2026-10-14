package com.qavideo.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * A bounded, seekable view of one region of a file.
 *
 * <p>Used to answer HTTP {@code Range} requests for video playback. The region is
 * streamed straight from disk, so seeking a 2 GB file costs no heap and no full read.
 *
 * <p>Why not Spring's {@code ResourceRegion}? Its message converter only accepts
 * {@code application/octet-stream}, so returning it together with a real video MIME type
 * fails with "No converter for [class ResourceRegion] with preset Content-Type 'video/mp4'"
 * - which is exactly what broke &lt;video&gt; playback (browsers always send Range).
 */
public final class RangeInputStream extends InputStream {

    private final InputStream delegate;
    private long remaining;

    private RangeInputStream(InputStream delegate, long remaining) {
        this.delegate = delegate;
        this.remaining = remaining;
    }

    /**
     * @param start  first byte to expose (0-based)
     * @param length number of bytes to expose
     */
    public static RangeInputStream of(Path file, long start, long length) throws IOException {
        InputStream in = Files.newInputStream(file, StandardOpenOption.READ);
        try {
            long skipped = 0L;
            while (skipped < start) {
                long step = in.skip(start - skipped);
                if (step <= 0L) {
                    // skip() may return 0 at EOF or for some streams; fall back to reading
                    if (in.read() < 0) {
                        break;
                    }
                    step = 1L;
                }
                skipped += step;
            }
            return new RangeInputStream(in, Math.max(0L, length));
        } catch (IOException ex) {
            in.close();
            throw ex;
        }
    }

    @Override
    public int read() throws IOException {
        if (remaining <= 0L) {
            return -1;
        }
        int value = delegate.read();
        if (value >= 0) {
            remaining--;
        }
        return value;
    }

    @Override
    public int read(byte[] buffer, int offset, int length) throws IOException {
        if (remaining <= 0L) {
            return -1;
        }
        int toRead = (int) Math.min(length, remaining);
        int read = delegate.read(buffer, offset, toRead);
        if (read > 0) {
            remaining -= read;
        }
        return read;
    }

    @Override
    public long skip(long n) throws IOException {
        long toSkip = Math.min(n, remaining);
        long skipped = delegate.skip(toSkip);
        remaining -= Math.max(0L, skipped);
        return skipped;
    }

    @Override
    public int available() throws IOException {
        return (int) Math.min(Integer.MAX_VALUE, Math.min(delegate.available(), remaining));
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}
