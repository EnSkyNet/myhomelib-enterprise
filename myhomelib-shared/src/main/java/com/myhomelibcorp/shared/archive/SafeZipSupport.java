package com.myhomelibcorp.shared.archive;

import com.myhomelibcorp.shared.util.AtomicFileSupport;
import org.apache.commons.compress.archivers.zip.ZipArchiveEntry;
import org.apache.commons.compress.archivers.zip.ZipFile;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;

/**
 * Opens external ZIP files through a single already-open {@code NOFOLLOW_LINKS} channel.
 *
 * <p>The legacy {@link ZipCharsetSupport} API is path based and therefore cannot prevent a
 * check/detect/reopen race. New security-sensitive code should use this helper instead. The
 * returned Commons Compress {@link ZipFile} owns the underlying channel and closes it when the
 * archive is closed.</p>
 */
public final class SafeZipSupport {
    private static final Charset[] LEGACY_CANDIDATES = {
            Charset.forName("Windows-1251"),
            Charset.forName("CP866"),
            Charset.forName("KOI8-R")
    };
    private static final int MAX_SAMPLED_ENTRIES = 256;

    private SafeZipSupport() { }

    public static ZipFile open(Path source, Charset charset, String role) throws IOException {
        Objects.requireNonNull(charset, "charset");
        Path safe = safeSource(source, role);
        FileChannel channel = FileChannel.open(safe, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            return build(channel, charset);
        } catch (IOException | RuntimeException failure) {
            closeSuppressed(channel, failure);
            throw failure;
        }
    }

    /**
     * Detects UTF-8/legacy Cyrillic entry-name encoding and returns an archive backed by the exact
     * file descriptor that was inspected. The pathname is never reopened after detection.
     */
    public static ZipFile openDetected(Path source, String role) throws IOException {
        Path safe = safeSource(source, role);
        FileChannel channel = FileChannel.open(safe, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        NonClosingSeekableByteChannel trialView = new NonClosingSeekableByteChannel(channel);
        IOException lastIo = null;
        boolean utf8Readable = false;
        try {
            boolean rawNamesAreUtf8 = false;
            try {
                channel.position(0L);
                try (ZipFile utf8 = build(trialView, StandardCharsets.UTF_8)) {
                    utf8Readable = true;
                    rawNamesAreUtf8 = sampledRawNamesAreUtf8(utf8);
                }
            } catch (IOException | IllegalArgumentException failure) {
                if (failure instanceof IOException io) lastIo = io;
            }

            if (rawNamesAreUtf8) {
                channel.position(0L);
                return build(channel, StandardCharsets.UTF_8);
            }

            Charset best = null;
            long bestScore = Long.MIN_VALUE;
            for (Charset candidate : LEGACY_CANDIDATES) {
                try {
                    channel.position(0L);
                    try (ZipFile trial = build(trialView, candidate)) {
                        long score = scoreEntryNames(trial);
                        if (best == null || score > bestScore) {
                            best = candidate;
                            bestScore = score;
                        }
                    }
                } catch (IOException | IllegalArgumentException failure) {
                    if (failure instanceof IOException io) lastIo = io;
                }
            }

            Charset selected = best != null ? best : (utf8Readable ? StandardCharsets.UTF_8 : null);
            if (selected == null) {
                throw new IOException("ZIP central directory cannot be decoded: " + source, lastIo);
            }
            channel.position(0L);
            return build(channel, selected);
        } catch (IOException | RuntimeException failure) {
            closeSuppressed(channel, failure);
            throw failure;
        }
    }

    private static Path safeSource(Path source, String role) throws IOException {
        Objects.requireNonNull(source, "source");
        String effectiveRole = role == null || role.isBlank() ? "ZIP archive" : role;
        return AtomicFileSupport.requireSafeRegularFile(source, effectiveRole);
    }

    private static ZipFile build(SeekableByteChannel channel, Charset charset) throws IOException {
        return ZipFile.builder()
                .setSeekableByteChannel(channel)
                .setCharset(charset)
                .get();
    }

    private static boolean sampledRawNamesAreUtf8(ZipFile zip) {
        int inspected = 0;
        var entries = zip.getEntries();
        while (entries.hasMoreElements() && inspected++ < MAX_SAMPLED_ENTRIES) {
            ZipArchiveEntry entry = entries.nextElement();
            if (entry.getGeneralPurposeBit() != null && entry.getGeneralPurposeBit().usesUTF8ForNames()) continue;
            if (entry.getNameSource() == ZipArchiveEntry.NameSource.UNICODE_EXTRA_FIELD) continue;
            byte[] raw = entry.getRawName();
            if (raw == null || raw.length == 0) continue;
            try {
                StandardCharsets.UTF_8.newDecoder()
                        .onMalformedInput(CodingErrorAction.REPORT)
                        .onUnmappableCharacter(CodingErrorAction.REPORT)
                        .decode(ByteBuffer.wrap(raw));
            } catch (java.nio.charset.CharacterCodingException invalidUtf8) {
                return false;
            }
        }
        return true;
    }

    private static long scoreEntryNames(ZipFile zip) {
        long score = 0L;
        int inspected = 0;
        var entries = zip.getEntries();
        while (entries.hasMoreElements() && inspected++ < MAX_SAMPLED_ENTRIES) {
            String name = entries.nextElement().getName();
            if (name == null) continue;
            for (int i = 0; i < name.length(); i++) score += score(name.charAt(i));
        }
        return score;
    }

    private static int score(char c) {
        if (c == '\ufffd') return -24;
        if (Character.isISOControl(c) && !Character.isWhitespace(c)) return -12;
        Character.UnicodeBlock block = Character.UnicodeBlock.of(c);
        if (block == Character.UnicodeBlock.BOX_DRAWING
                || block == Character.UnicodeBlock.BLOCK_ELEMENTS
                || block == Character.UnicodeBlock.GEOMETRIC_SHAPES) return -8;
        if (Character.UnicodeScript.of(c) == Character.UnicodeScript.CYRILLIC) return 4;
        if (Character.isLetterOrDigit(c)) return 2;
        if (Character.isWhitespace(c) || c == '/' || c == '.' || c == '-' || c == '_'
                || c == '(' || c == ')' || c == '[' || c == ']') return 1;
        return 0;
    }

    private static void closeSuppressed(FileChannel channel, Throwable failure) {
        try {
            channel.close();
        } catch (IOException closeFailure) {
            failure.addSuppressed(closeFailure);
        }
    }

    private static final class NonClosingSeekableByteChannel implements SeekableByteChannel {
        private final SeekableByteChannel delegate;

        private NonClosingSeekableByteChannel(SeekableByteChannel delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override public int read(ByteBuffer dst) throws IOException { return delegate.read(dst); }
        @Override public int write(ByteBuffer src) throws IOException { return delegate.write(src); }
        @Override public long position() throws IOException { return delegate.position(); }
        @Override public SeekableByteChannel position(long newPosition) throws IOException { delegate.position(newPosition); return this; }
        @Override public long size() throws IOException { return delegate.size(); }
        @Override public SeekableByteChannel truncate(long size) throws IOException { delegate.truncate(size); return this; }
        @Override public boolean isOpen() { return delegate.isOpen(); }
        @Override public void close() { /* trial parsers do not own the real channel */ }
    }
}
