package com.myhomelibcorp.infrastructure.archive;

import com.myhomelibcorp.application.util.SafeFilePublication;
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
 * Opens a ZIP container through one already-open no-follow channel.
 * This avoids the check-then-reopen pathname race inherent in path/File based ZIP APIs.
 */
public final class SafeZipArchive implements AutoCloseable {
    private static final Charset[] LEGACY_CANDIDATES = {
            Charset.forName("Windows-1251"),
            Charset.forName("CP866"),
            Charset.forName("KOI8-R")
    };
    private static final int MAX_SAMPLED_ENTRIES = 256;
    private final FileChannel channel;
    private final ZipFile zip;

    private SafeZipArchive(FileChannel channel, ZipFile zip) {
        this.channel = channel;
        this.zip = zip;
    }

    public static SafeZipArchive open(Path source, Charset charset, String role) throws IOException {
        Objects.requireNonNull(charset, "charset");
        Path safe = SafeFilePublication.requireSafeRegularFile(source, role);
        FileChannel channel = FileChannel.open(safe, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        try {
            ZipFile zip = ZipFile.builder()
                    .setSeekableByteChannel(channel)
                    .setCharset(charset)
                    .get();
            return new SafeZipArchive(channel, zip);
        } catch (IOException | RuntimeException failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    /**
     * Opens a ZIP using UTF-8 when possible, otherwise keeps the best-scoring legacy
     * Cyrillic decoding. The selected archive stays on the exact channel that was
     * inspected, avoiding a detect-then-reopen pathname race.
     */
    public static SafeZipArchive openDetected(Path source, String role) throws IOException {
        Objects.requireNonNull(source, "source");
        Path safe = SafeFilePublication.requireSafeRegularFile(source, role);
        FileChannel channel = FileChannel.open(safe, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        NonClosingSeekableByteChannel view = new NonClosingSeekableByteChannel(channel);
        IOException lastIo = null;
        boolean utf8Readable = false;
        try {
            // Prefer UTF-8 whenever the raw central-directory names are strictly valid UTF-8.
            // This is important because decoding genuine UTF-8 Cyrillic as Windows-1251 also
            // produces lots of Cyrillic-looking characters (classic "Р..." mojibake), which can
            // otherwise win a naive script-frequency score.
            boolean rawNamesAreUtf8 = false;
            try {
                channel.position(0L);
                try (ZipFile utf8 = buildZip(view, StandardCharsets.UTF_8)) {
                    utf8Readable = true;
                    rawNamesAreUtf8 = sampledRawNamesAreUtf8(utf8);
                }
            } catch (IOException | IllegalArgumentException failure) {
                if (failure instanceof IOException io) lastIo = io;
            }
            if (rawNamesAreUtf8) {
                channel.position(0L);
                return new SafeZipArchive(channel, buildZip(view, StandardCharsets.UTF_8));
            }

            Charset bestCharset = null;
            long bestScore = Long.MIN_VALUE;
            for (Charset candidate : LEGACY_CANDIDATES) {
                try {
                    channel.position(0L);
                    try (ZipFile inspected = buildZip(view, candidate)) {
                        long score = scoreEntryNames(inspected);
                        if (bestCharset == null || score > bestScore) {
                            bestCharset = candidate;
                            bestScore = score;
                        }
                    }
                } catch (IOException | IllegalArgumentException failure) {
                    if (failure instanceof IOException io) lastIo = io;
                }
            }

            Charset selected = bestCharset != null
                    ? bestCharset
                    : (utf8Readable ? StandardCharsets.UTF_8 : null);
            if (selected == null) {
                throw new IOException("ZIP central directory cannot be decoded: " + source, lastIo);
            }
            channel.position(0L);
            return new SafeZipArchive(channel, buildZip(view, selected));
        } catch (IOException | RuntimeException failure) {
            try {
                channel.close();
            } catch (IOException closeFailure) {
                failure.addSuppressed(closeFailure);
            }
            throw failure;
        }
    }

    private static boolean sampledRawNamesAreUtf8(ZipFile zip) {
        int inspected = 0;
        var entries = zip.getEntries();
        while (entries.hasMoreElements() && inspected++ < MAX_SAMPLED_ENTRIES) {
            var entry = entries.nextElement();
            if (entry.getGeneralPurposeBit() != null && entry.getGeneralPurposeBit().usesUTF8ForNames()) continue;
            if (entry.getNameSource() == org.apache.commons.compress.archivers.zip.ZipArchiveEntry.NameSource.UNICODE_EXTRA_FIELD) continue;
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

    private static ZipFile buildZip(SeekableByteChannel channel, Charset charset) throws IOException {
        return ZipFile.builder()
                .setSeekableByteChannel(channel)
                .setCharset(charset)
                .get();
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

    public ZipFile zip() {
        return zip;
    }

    public long size() throws IOException {
        return channel.size();
    }

    /** SHA-256 of the exact bytes backing this already-open ZIP handle. */
    public String sha256(String role) throws IOException {
        return SafeFilePublication.sha256(channel, role);
    }

    public byte[] readPrefix(int maxBytes) throws IOException {
        if (maxBytes < 0) throw new IllegalArgumentException("maxBytes must be >= 0");
        int length = (int) Math.min(channel.size(), (long) maxBytes);
        ByteBuffer buffer = ByteBuffer.allocate(length);
        long position = 0L;
        while (buffer.hasRemaining()) {
            int read = channel.read(buffer, position);
            if (read < 0) break;
            if (read == 0) break;
            position += read;
        }
        byte[] result = new byte[buffer.position()];
        buffer.flip();
        buffer.get(result);
        return result;
    }


    /**
     * Lets temporary ZIP parsers share one already-open file descriptor while keeping ownership
     * of the real channel in {@link SafeZipArchive}. Apache Commons Compress closes the channel
     * supplied to a ZipFile, so trial parsers must receive a non-closing view.
     */
    private static final class NonClosingSeekableByteChannel implements SeekableByteChannel {
        private final SeekableByteChannel delegate;

        private NonClosingSeekableByteChannel(SeekableByteChannel delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override public int read(ByteBuffer dst) throws IOException { return delegate.read(dst); }
        @Override public int write(ByteBuffer src) throws IOException { return delegate.write(src); }
        @Override public long position() throws IOException { return delegate.position(); }
        @Override public SeekableByteChannel position(long newPosition) throws IOException {
            delegate.position(newPosition);
            return this;
        }
        @Override public long size() throws IOException { return delegate.size(); }
        @Override public SeekableByteChannel truncate(long size) throws IOException {
            delegate.truncate(size);
            return this;
        }
        @Override public boolean isOpen() { return delegate.isOpen(); }
        @Override public void close() { /* owner closes the underlying channel */ }
    }

    @Override
    public void close() throws IOException {
        IOException failure = null;
        try {
            zip.close();
        } catch (IOException closeFailure) {
            failure = closeFailure;
        }
        try {
            channel.close();
        } catch (IOException closeFailure) {
            if (failure == null) failure = closeFailure;
            else failure.addSuppressed(closeFailure);
        }
        if (failure != null) throw failure;
    }
}
