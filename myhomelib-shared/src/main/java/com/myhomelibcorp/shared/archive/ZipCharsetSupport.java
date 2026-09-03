package com.myhomelibcorp.shared.archive;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Detects legacy ZIP filename encodings without letting a permissive single-byte
 * decoder pre-empt UTF-8 or a better Cyrillic match.
 *
 * <p>ZIP archives produced by modern tools mark UTF-8 names explicitly. Older
 * Windows/DOS archives may store names in Windows-1251, CP866 or KOI8-R. Those
 * single-byte charsets can decode almost any byte sequence, so a naive
 * "try CP866 first" loop does not actually provide fallback and can persist
 * mojibake entry names. This helper first accepts a valid UTF-8 central
 * directory, otherwise scores a small sample of names decoded with supported
 * legacy Cyrillic charsets.</p>
 */
public final class ZipCharsetSupport {
    private static final Charset[] LEGACY_CANDIDATES = {
            Charset.forName("Windows-1251"),
            Charset.forName("CP866"),
            Charset.forName("KOI8-R")
    };
    private static final int MAX_SAMPLED_ENTRIES = 256;

    private ZipCharsetSupport() { }

    public static Charset detect(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        try (ZipFile ignored = new ZipFile(file.toFile(), StandardCharsets.UTF_8)) {
            return StandardCharsets.UTF_8;
        } catch (IOException | IllegalArgumentException utf8Failure) {
            Charset best = null;
            long bestScore = Long.MIN_VALUE;
            IOException lastIo = utf8Failure instanceof IOException io ? io : null;

            for (Charset candidate : LEGACY_CANDIDATES) {
                try (ZipFile zip = new ZipFile(file.toFile(), candidate)) {
                    long score = scoreEntryNames(zip);
                    if (best == null || score > bestScore) {
                        best = candidate;
                        bestScore = score;
                    }
                } catch (IOException | IllegalArgumentException failure) {
                    if (failure instanceof IOException io) lastIo = io;
                }
            }
            if (best != null) return best;
            throw new IOException("ZIP central directory cannot be decoded: " + file, lastIo);
        }
    }

    public static ZipFile open(Path file) throws IOException {
        return new ZipFile(file.toFile(), detect(file));
    }

    private static long scoreEntryNames(ZipFile zip) {
        long score = 0;
        int inspected = 0;
        Enumeration<? extends ZipEntry> entries = zip.entries();
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
}
