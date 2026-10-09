package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.shared.util.FileNameSupport;
import com.myhomelibcorp.shared.format.SupportedFormat;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.BookArtifactDto;
import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Application boundary for resolving a catalogue book into one safe physical
 * document. UI never needs to call storage/archive output ports directly.
 */
@Component
@RequiredArgsConstructor
public class ResolveBookContentUseCase {
    private static final SupportedFormatRegistry FORMATS = SupportedFormatRegistry.standard();
    public static final Set<String> READER_EXTENSIONS = FORMATS.extensions(
            f -> f.family() == SupportedFormat.Family.BOOK && f.readerSupported());
    public static final Set<String> DETAILS_EXTENSIONS = FORMATS.extensions(
            f -> f.family() == SupportedFormat.Family.BOOK && (f.readerSupported() || f.coverSupported()));

    private final BookResourcePort resourcePort;

    public ResolvedBookContent execute(BookDto book, Set<String> allowedExtensions) throws IOException {
        if (book == null) throw new IOException("Книга не задана");

        IOException lastFailure = null;
        for (BookDto candidate : readableProjectionCandidates(book)) {
            try {
                return resolveSingleProjection(candidate, allowedExtensions);
            } catch (IOException failure) {
                lastFailure = failure;
            }
        }
        if (lastFailure != null) throw lastFailure;
        throw new IOException("Локальний файл книги не знайдено");
    }

    private ResolvedBookContent resolveSingleProjection(BookDto book, Set<String> allowedExtensions) throws IOException {
        Path physical = resourcePort.locateBookFile(
                        book.getFileName(), book.getFolder(), book.getCollectionRoot(), book.getArchiveEntry())
                .orElseThrow(() -> new IOException("Локальний файл книги не знайдено"));

        if (!resourcePort.isArchive(physical.toString())) return new ResolvedBookContent(physical, false);

        // Some reader-native documents are themselves archive containers (CBZ/CBR).
        // When the physical container extension is explicitly allowed, preserve it whole
        // instead of selecting one member as if it were a generic ZIP/RAR bundle.
        if (allowed(allowedExtensions, physical.getFileName() == null ? "" : physical.getFileName().toString())) {
            return new ResolvedBookContent(physical, false);
        }

        String selected = trim(book.getArchiveEntry());
        if (selected.isBlank()) {
            selected = resourcePort.listArchiveEntries(physical).stream()
                    .filter(name -> allowed(allowedExtensions, name))
                    .findFirst().orElse("");
        }
        if (selected.isBlank() || !allowed(allowedExtensions, selected)) {
            throw new IOException("В архіві не знайдено підтримуваний файл книги");
        }

        Optional<InputStream> opened = trim(book.getArchiveEntry()).isBlank()
                ? resourcePort.readArchiveEntry(physical, selected)
                : resourcePort.readBookData(
                        book.getFileName(), book.getFolder(), book.getCollectionRoot(), book.getArchiveEntry());
        if (opened.isEmpty()) throw new IOException("Не вдалося прочитати запис архіву: " + selected);
        Path temp = Files.createTempFile("myhomelib-book-content-", suffix(selected));
        boolean success = false;
        try (InputStream in = opened.get(); OutputStream out = Files.newOutputStream(temp)) {
            byte[] buffer = new byte[64 * 1024];
            long total = 0;
            int read;
            while ((read = in.read(buffer)) >= 0) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedIOException("Book content resolution cancelled");
                }
                if (read == 0) continue;
                total += read;
                if (total > ArchiveSafetyLimits.MAX_ENTRY_BYTES) {
                    throw new IOException("Запис книги в архіві перевищує безпечний ліміт");
                }
                out.write(buffer, 0, read);
            }
            success = true;
            return new ResolvedBookContent(temp, true);
        } finally {
            if (!success) Files.deleteIfExists(temp);
        }
    }

    /**
     * Returns physical projections in a stable preference order. A stale generated preferred artifact
     * must not hide a still-readable original/imported representation. The original DTO is retained as
     * a final legacy fallback for catalogues created before the multi-artifact schema.
     */
    private static List<BookDto> readableProjectionCandidates(BookDto book) {
        if (book.getArtifacts().isEmpty()) return List.of(book);

        List<BookDto> result = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        BookArtifactDto preferred = book.getPreferredArtifact();
        addArtifactProjection(result, seen, book, preferred);

        book.getArtifacts().stream()
                .filter(ResolveBookContentUseCase::availableOriginal)
                .forEach(a -> addArtifactProjection(result, seen, book, a));
        book.getArtifacts().stream()
                .filter(ResolveBookContentUseCase::availableArtifact)
                .forEach(a -> addArtifactProjection(result, seen, book, a));
        book.getArtifacts().stream()
                .filter(a -> a != null && !derivedConversion(a))
                .forEach(a -> addArtifactProjection(result, seen, book, a));
        book.getArtifacts().forEach(a -> addArtifactProjection(result, seen, book, a));

        String legacyKey = storageKey(book.getFileName(), book.getFolder(), book.getCollectionRoot(), book.getArchiveEntry());
        if (seen.add(legacyKey)) result.add(book);
        return List.copyOf(result);
    }

    private static void addArtifactProjection(List<BookDto> target, Set<String> seen, BookDto book, BookArtifactDto artifact) {
        if (artifact == null) return;
        String key = storageKey(artifact.getFileName(), artifact.getFolder(), artifact.getCollectionRoot(), artifact.getArchiveEntry());
        if (seen.add(key)) target.add(book.projectArtifact(artifact));
    }

    private static boolean availableOriginal(BookArtifactDto artifact) {
        return availableArtifact(artifact) && !derivedConversion(artifact);
    }

    private static boolean availableArtifact(BookArtifactDto artifact) {
        return artifact != null && artifact.isLocal() && "AVAILABLE".equalsIgnoreCase(trim(artifact.getState()));
    }

    private static boolean derivedConversion(BookArtifactDto artifact) {
        if (artifact == null) return false;
        String sourceId = trim(artifact.getSourceId());
        if (sourceId.regionMatches(true, 0, "conversion:", 0, "conversion:".length())) return true;
        var metadata = artifact.getMetadata();
        return metadata.containsKey("conversion.sourceFormat")
                || metadata.containsKey("conversion.provider")
                || metadata.containsKey("conversion.createdAt");
    }

    private static String storageKey(String fileName, String folder, String root, String entry) {
        return trim(root) + "\u0000" + trim(folder) + "\u0000" + trim(fileName) + "\u0000" + trim(entry);
    }

    private static boolean allowed(Set<String> allowed, String name) {
        return allowed != null && allowed.contains(FileNameSupport.extension(name));
    }


    private static String suffix(String name) {
        String ext = FileNameSupport.extension(name);
        return ext.isBlank() ? ".book" : "." + ext;
    }

    private static String trim(String value) { return value == null ? "" : value.trim(); }
}
