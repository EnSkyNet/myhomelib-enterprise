package com.myhomelibcorp.infrastructure.cover;

import com.myhomelibcorp.shared.util.FileNameSupport;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.CoverLocator;
import com.myhomelibcorp.application.port.out.cover.CoverReader;
import com.myhomelibcorp.infrastructure.archive.ArchiveEntryNameSupport;
import com.myhomelibcorp.infrastructure.image.EpubCoverParser;
import com.myhomelibcorp.infrastructure.image.FallbackCoverRenderer;
import com.myhomelibcorp.infrastructure.image.Fb2CoverParser;
import com.myhomelibcorp.infrastructure.image.MobiCoverParser;
import com.myhomelibcorp.infrastructure.image.PdfCoverParser;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.function.Predicate;

@Component
@RequiredArgsConstructor
@Slf4j
public class CoverReaderImpl implements CoverReader {

    private final CoverLocator coverLocator;
    private final ZipArchiveReader archiveReader;
    private final Fb2CoverParser fb2CoverParser;
    private final EpubCoverParser epubCoverParser;
    private final MobiCoverParser mobiCoverParser;
    private final PdfCoverParser pdfCoverParser;
    private final FallbackCoverRenderer fallbackCoverRenderer;

    @Override
    public byte[] readCover(BookDto book) {
        if (book == null) return null;
        long started = System.nanoTime();
        log.info("COVER_START id={} title='{}' file='{}' archiveEntry='{}' preferredArtifact={}",
                safeLog(book.getId()), safeLog(book.getTitle()), safeLog(book.getFileName()),
                safeLog(book.getArchiveEntry()), safeLog(book.getPreferredArtifactId()));
        try {
            checkCancelled();
            Path filePath = coverLocator.locateCoverFile(book).orElse(null);
            if (filePath == null || !Files.isRegularFile(filePath, LinkOption.NOFOLLOW_LINKS)) {
                log.info("COVER_NONE id={} durationMs={} reason=source-unavailable file='{}' archiveEntry='{}'",
                        safeLog(book.getId()), elapsedMillis(started), safeLog(book.getFileName()), safeLog(book.getArchiveEntry()));
                return null;
            }
            byte[] cover = archiveReader.isArchive(filePath)
                    ? extractFromArchive(filePath, book)
                    : extractFromDocument(filePath, FileNameSupport.extension(filePath.getFileName().toString()), book);
            checkCancelled();
            if (cover == null || cover.length == 0) {
                log.info("COVER_NONE id={} durationMs={} reason=no-extractable-cover source='{}'",
                        safeLog(book.getId()), elapsedMillis(started), filePath);
                return null;
            }
            log.info("COVER_DONE id={} durationMs={} bytes={} source='{}'",
                    safeLog(book.getId()), elapsedMillis(started), cover.length, filePath);
            return cover;
        } catch (Exception e) {
            if (e instanceof InterruptedIOException || Thread.currentThread().isInterrupted()) {
                log.info("COVER_CANCEL id={} durationMs={} reason='{}'",
                        safeLog(book.getId()), elapsedMillis(started), safeLog(e.getMessage()));
            } else {
                log.warn("COVER_FAIL id={} durationMs={} file='{}' archiveEntry='{}' error='{}'",
                        safeLog(book.getId()), elapsedMillis(started), safeLog(book.getFileName()),
                        safeLog(book.getArchiveEntry()), safeLog(e.getMessage()));
            }
            return null;
        }
    }

    private byte[] extractFromDocument(Path path, String ext, BookDto book) throws IOException {
        String normalized = ext.toLowerCase(Locale.ROOT);
        return switch (normalized) {
            case "fb2", "fbd" -> {
                try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) { yield fb2CoverParser.parseToBytes(in); }
            }
            case "epub" -> epubCoverParser.parse(path);
            case "mobi", "prc", "azw", "azw3" -> fallbackIfEmpty(mobiCoverParser.parse(path), normalized, book);
            case "pdf" -> fallbackIfEmpty(pdfCoverParser.parse(path), normalized, book);
            case "djvu", "djv" -> fallbackCoverRenderer.render("DJVU", book.getTitle());
            default -> null;
        };
    }

    private byte[] extractFromArchive(Path archivePath, BookDto book) {
        List<String> entries = archiveReader.listEntries(archivePath);
        if (entries.isEmpty()) return null;

        String targetEntry = findBestEntry(entries, book);
        if (targetEntry != null) {
            String ext = FileNameSupport.extension(targetEntry);
            try (InputStream in = archiveReader.readEntry(archivePath, targetEntry).orElse(null)) {
                if (in != null) {
                    if (isImageExtension(ext)) return readBounded(in, 24 * 1024 * 1024);
                    if (ext.equals("fb2") || ext.equals("fbd")) return fb2CoverParser.parseToBytes(in);
                    if (isExtraDocument(ext)) {
                        Path temp = materialize(in, ext);
                        try { return extractFromDocument(temp, ext, book); }
                        finally { Files.deleteIfExists(temp); }
                    }
                }
            } catch (Exception e) {
                log.debug("Не вдалося витягти cover з {}!{}: {}", archivePath, targetEntry, e.getMessage());
            }
        }

        // A requested catalogue archive entry identifies one logical book. If it cannot be
        // resolved unambiguously, never return an arbitrary image/document from another book in
        // the same shared archive. Image-only comic archives without archiveEntry keep the
        // traditional first-image cover behavior.
        if (book.getArchiveEntry() != null && !book.getArchiveEntry().isBlank()) {
            log.debug("Cover archive entry unresolved for id={} requested='{}' archive='{}'",
                    book.getId(), book.getArchiveEntry(), archivePath);
            return null;
        }
        long documentCount = entries.stream()
                .filter(entry -> isSupportedDocument(FileNameSupport.extension(entry)))
                .limit(2)
                .count();
        if (documentCount > 1) {
            log.debug("Cover archive entry ambiguous for id={} archive='{}'", book.getId(), archivePath);
            return null;
        }
        try (InputStream image = archiveReader.findFirstEntry(archivePath,
                name -> isImageExtension(FileNameSupport.extension(name))).orElse(null)) {
            return image == null ? null : readBounded(image, 24 * 1024 * 1024);
        } catch (Exception e) {
            log.debug("Не вдалося прочитати fallback cover з {}: {}", archivePath, e.getMessage());
            return null;
        }
    }

    private String findBestEntry(List<String> entries, BookDto book) {
        String archiveEntry = book.getArchiveEntry();
        String fileName = book.getFileName();
        if (archiveEntry != null && !archiveEntry.isBlank()) {
            String normalizedRequested = ArchiveEntryNameSupport.normalizePath(archiveEntry);
            Optional<String> exact = entries.stream()
                    .filter(entry -> ArchiveEntryNameSupport.normalizePath(entry).equalsIgnoreCase(normalizedRequested))
                    .findFirst();
            if (exact.isPresent()) return exact.orElseThrow();

            Optional<String> logical = uniqueEntry(entries,
                    entry -> ArchiveEntryNameSupport.sameLogicalPath(entry, archiveEntry));
            if (logical.isPresent()) return logical.orElseThrow();

            String requestedStem = stripExtension(ArchiveEntryNameSupport.baseName(archiveEntry));
            Optional<String> byRequested = uniqueSupportedToken(entries, requestedStem);
            if (byRequested.isPresent()) return byRequested.orElseThrow();

            String fileStem = stripArchiveAndDocumentExtensions(ArchiveEntryNameSupport.baseName(fileName));
            if (!fileStem.isBlank() && !fileStem.equalsIgnoreCase(requestedStem)) {
                Optional<String> byFile = uniqueSupportedToken(entries, fileStem);
                if (byFile.isPresent()) return byFile.orElseThrow();
            }

            String bookId = book.getId() == null ? "" : book.getId().trim();
            if (!bookId.isBlank()) {
                Optional<String> byBookId = uniqueSupportedToken(entries, bookId);
                if (byBookId.isPresent()) return byBookId.orElseThrow();
            }

            List<String> supported = entries.stream()
                    .filter(entry -> isSupportedDocument(FileNameSupport.extension(entry)))
                    .toList();
            return supported.size() == 1 ? supported.getFirst() : null;
        }
        if (fileName != null && !fileName.isBlank() && !archiveReader.isArchiveName(fileName)) {
            Optional<String> byFile = uniqueEntry(entries,
                    entry -> ArchiveEntryNameSupport.sameLogicalPath(entry, fileName));
            if (byFile.isPresent()) return byFile.orElseThrow();
        }
        String title = book.getTitle();
        if (title != null && !title.isBlank()) {
            String normalizedTitle = normalizeTitle(title);
            Optional<String> byTitle = uniqueEntry(entries, entry ->
                    isSupportedDocument(FileNameSupport.extension(entry))
                            && normalizeTitle(FileNameSupport.baseName(entry)).contains(normalizedTitle));
            if (byTitle.isPresent()) return byTitle.orElseThrow();
        }
        List<String> supported = entries.stream()
                .filter(entry -> isSupportedDocument(FileNameSupport.extension(entry)))
                .toList();
        return supported.size() == 1 ? supported.getFirst() : null;
    }

    private static Optional<String> uniqueSupportedToken(List<String> entries, String token) {
        if (token == null || token.isBlank()) return Optional.empty();
        return uniqueEntry(entries, entry -> isSupportedDocument(FileNameSupport.extension(entry))
                && ArchiveEntryNameSupport.containsDelimitedToken(
                ArchiveEntryNameSupport.baseName(entry), token));
    }

    private static Optional<String> uniqueEntry(List<String> entries, Predicate<String> predicate) {
        List<String> matches = entries.stream().filter(predicate).limit(2).toList();
        return matches.size() == 1 ? Optional.of(matches.getFirst()) : Optional.empty();
    }

    private static String stripArchiveAndDocumentExtensions(String value) {
        String result = value == null ? "" : value.trim();
        String previous;
        do {
            previous = result;
            String lower = result.toLowerCase(Locale.ROOT);
            for (String suffix : List.of(".fb2", ".fbd", ".epub", ".zip", ".fb2zip", ".cbz", ".jar")) {
                if (lower.endsWith(suffix)) {
                    result = result.substring(0, result.length() - suffix.length());
                    break;
                }
            }
        } while (!result.equals(previous));
        return result;
    }

    private static String stripExtension(String value) {
        if (value == null) return "";
        int dot = value.lastIndexOf('.');
        return dot > 0 ? value.substring(0, dot) : value;
    }

    private static String normalizeTitle(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s_\\-]+", "");
    }

    private byte[] fallbackIfEmpty(byte[] bytes, String format, BookDto book) {
        return bytes != null && bytes.length > 0 ? bytes : fallbackCoverRenderer.render(format, book.getTitle());
    }

    private static boolean isSupportedDocument(String ext) {
        return SupportedFormatRegistry.standard().detect("book." + ext)
                .map(format -> format.coverSupported())
                .orElse(false);
    }

    private static boolean isExtraDocument(String ext) {
        return isSupportedDocument(ext) && !SupportedFormatRegistry.standard().isFormat(Path.of("book." + ext), "fb2");
    }

    private static boolean isImageExtension(String ext) {
        return switch (ext) {
            case "jpg", "jpeg", "png", "gif", "webp" -> true;
            default -> false;
        };
    }


    private static Path materialize(InputStream in, String ext) throws IOException {
        Path temp = Files.createTempFile("myhomelib-cover-", ext.isBlank() ? ".book" : "." + ext);
        boolean success = false;
        try (OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                checkCancelled();
                if (read == 0) continue;
                total += read;
                if (total > ArchiveSafetyLimits.MAX_ENTRY_BYTES) throw new IOException("Archive entry exceeds cover safety limit");
                out.write(buffer, 0, read);
            }
            success = true;
            return temp;
        } finally {
            if (!success) Files.deleteIfExists(temp);
        }
    }

    private static byte[] readBounded(InputStream in, int max) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(Math.min(max, 64 * 1024));
        byte[] buffer = new byte[32 * 1024];
        int total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            checkCancelled();
            if (read == 0) continue;
            total += read;
            if (total > max) throw new IOException("Cover exceeds safe image limit");
            out.write(buffer, 0, read);
        }
        checkCancelled();
        return out.toByteArray();
    }

    private static long elapsedMillis(long startedNanos) {
        return Math.max(0L, java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
    }

    private static String safeLog(String value) {
        return value == null ? "" : value.replace('\r', ' ').replace('\n', ' ').trim();
    }

    private static void checkCancelled() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) {
            throw new InterruptedIOException("Cover extraction cancelled");
        }
    }
}
