package com.myhomelibcorp.ui.reader;

import com.myhomelibcorp.reader.api.TocEntry;
import com.myhomelibcorp.shared.format.SupportedFormat;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import com.myhomelibcorp.ui.util.UiExceptionSupport;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pure/support operations extracted from the JavaFX reader workspace controller. */
final class ReaderWorkspaceSupport {
    private ReaderWorkspaceSupport() { }

    static boolean isComicExtension(String extension) {
        return "cbz".equalsIgnoreCase(extension) || "cbr".equalsIgnoreCase(extension);
    }

    static boolean isAudioExtension(String extension) {
        return "mp3".equalsIgnoreCase(extension) || "m4b".equalsIgnoreCase(extension);
    }

    static void deleteTemp(Path temp) {
        if (temp == null) return;
        try { Files.deleteIfExists(temp); } catch (IOException ignored) { }
    }

    static Throwable unwrap(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && (current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException)) {
            current = current.getCause();
        }
        return current;
    }

    static String rootMessage(Throwable error) {
        return UiExceptionSupport.message(error);
    }

    static double millis(long nanos) {
        return Math.round((nanos / 1_000_000.0) * 10.0) / 10.0;
    }

    static boolean isReaderEntry(String name) {
        return SupportedFormatRegistry.standard().detect(name)
                .filter(format -> format.family() == SupportedFormat.Family.BOOK)
                .map(SupportedFormat::readerSupported)
                .orElse(false);
    }

    static String readerSuffix(String name) {
        if (name == null) return ".book";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        return dot > slash ? name.substring(dot) : ".book";
    }

    static AtomicBoolean replaceCancellation(AtomicReference<AtomicBoolean> ref) {
        AtomicBoolean next = new AtomicBoolean(false);
        AtomicBoolean previous = ref.getAndSet(next);
        if (previous != null) previous.set(true);
        return next;
    }

    static void cancelRequest(AtomicReference<AtomicBoolean> ref) {
        AtomicBoolean active = ref.getAndSet(null);
        if (active != null) active.set(true);
    }

    static String normalizeLanguageCode(String value) {
        String normalized = normalizeOptionalLanguageCode(value);
        return normalized.isBlank() ? "uk" : normalized;
    }

    static String normalizeOptionalLanguageCode(String value) {
        if (value == null || value.isBlank()) return "";
        String normalized = value.trim().replace('_', '-').toLowerCase(Locale.ROOT);
        int dash = normalized.indexOf('-');
        return dash < 0 ? normalized : normalized.substring(0, dash);
    }

    static List<TocEntry> flattenToc(List<TocEntry> roots) {
        List<TocEntry> result = new ArrayList<>();
        appendToc(roots, result, 0);
        return List.copyOf(result);
    }

    private static void appendToc(List<TocEntry> entries, List<TocEntry> target, int inheritedLevel) {
        if (entries == null) return;
        for (TocEntry entry : entries) {
            if (entry == null) continue;
            int level = Math.max(inheritedLevel, entry.level());
            target.add(new TocEntry(entry.title(), entry.textOffset(), level, List.of()));
            appendToc(entry.children(), target, level + 1);
        }
    }
}
