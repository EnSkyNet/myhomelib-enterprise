package com.myhomelibcorp.application.reader;

import com.myhomelibcorp.application.port.out.resource.BookResourcePort;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.shared.format.SupportedFormat;
import com.myhomelibcorp.shared.format.SupportedFormatRegistry;
import com.myhomelibcorp.shared.archive.ArchiveSafetyLimits;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Optional;

/** Selects and materializes a physically readable Reader source without mutating persisted preference state. */
public final class ReaderSourceResolver {
    private ReaderSourceResolver() { }

    public static Optional<SourceChoice> resolve(Book book, String requestedArtifactId, BookResourcePort bookResourcePort) {
        if (book == null || bookResourcePort == null) return Optional.empty();

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        if (requestedArtifactId != null && !requestedArtifactId.isBlank()) candidates.add(requestedArtifactId.trim());
        if (book.getPreferredArtifactId() != null && !book.getPreferredArtifactId().isBlank()) {
            candidates.add(book.getPreferredArtifactId());
        }

        // If the preferred/generated representation vanished, prefer an original/imported artifact
        // before another derived conversion. Resolve only the physical container here; archive-member
        // compatibility is intentionally resolved exactly once during bounded materialization.
        book.getArtifacts().stream()
                .filter(a -> !isDerivedReaderArtifact(a))
                .filter(BookArtifact::isAvailable)
                .map(BookArtifact::getId)
                .forEach(candidates::add);
        book.getArtifacts().stream()
                .filter(BookArtifact::isAvailable)
                .map(BookArtifact::getId)
                .forEach(candidates::add);
        book.getArtifacts().stream()
                .filter(a -> !isDerivedReaderArtifact(a))
                .map(BookArtifact::getId)
                .forEach(candidates::add);
        book.getArtifacts().stream().map(BookArtifact::getId).forEach(candidates::add);

        String persistedPreferred = book.getPreferredArtifactId();
        for (String artifactId : candidates) {
            Book candidate;
            try {
                candidate = book.selectPreferredArtifact(artifactId);
            } catch (IllegalArgumentException staleArtifactId) {
                continue;
            }
            Optional<Path> physical = bookResourcePort.locateBookContainer(candidate);
            if (physical.isPresent()) {
                return Optional.of(new SourceChoice(candidate, physical.get(), artifactId,
                        persistedPreferred != null && !persistedPreferred.equals(artifactId)));
            }
        }

        // Legacy rows without artifact projections still remain readable through books.*.
        if (book.getArtifacts().isEmpty()) {
            Optional<Path> physical = bookResourcePort.locateBookContainer(book);
            if (physical.isPresent()) {
                return Optional.of(new SourceChoice(book, physical.get(), null, false));
            }
        }
        return Optional.empty();
    }

    public static MaterializedSource materialize(Book book, Path physicalPath, BookResourcePort bookResourcePort,
                                           String archiveEntryReadError) throws IOException {
        String selectedEntry = book.getArchiveEntry();
        boolean physicalArchive = bookResourcePort.isArchive(physicalPath.toString());
        if ((selectedEntry == null || selectedEntry.isBlank()) && !physicalArchive) {
            return new MaterializedSource(physicalPath, null);
        }
        if ((selectedEntry == null || selectedEntry.isBlank()) && physicalArchive) {
            // Keep the complete archive. ZipParser will preserve every supported book
            // and build a hierarchical book -> chapter TOC instead of silently opening
            // only the first member.
            return new MaterializedSource(physicalPath, null);
        }
        if (selectedEntry == null || selectedEntry.isBlank() || !isReaderEntry(selectedEntry)) {
            return new MaterializedSource(physicalPath, null);
        }

        Path temp = Files.createTempFile("myhomelib-reader-book-", readerSuffix(selectedEntry));
        boolean success = false;
        try {
            Optional<String> actualEntry = bookResourcePort.materializeArchiveBookEntry(
                    book, physicalPath, temp, ArchiveSafetyLimits.MAX_ENTRY_BYTES,
                    () -> Thread.currentThread().isInterrupted());
            if (actualEntry.isEmpty()) throw new IOException(archiveEntryReadError);
            success = true;
            return new MaterializedSource(temp, temp);
        } finally {
            if (!success) Files.deleteIfExists(temp);
        }
    }

    /** Resolves the artifact whose file projection is actually opened by the current Reader. */
    public static String resolveOpenedArtifactId(Book book) {
        if (book == null || book.getArtifacts().isEmpty()) return null;
        BookFile opened = book.getFile();
        return book.getArtifacts().stream()
                .filter(artifact -> BookArtifactFileIdentity.matches(opened, artifact))
                .map(BookArtifact::getId)
                .findFirst()
                .orElse(null);
    }

    private static boolean isDerivedReaderArtifact(BookArtifact artifact) {
        if (artifact == null) return false;
        String sourceId = artifact.getSourceId();
        if (sourceId != null && sourceId.regionMatches(true, 0, "conversion:", 0, "conversion:".length())) return true;
        var metadata = artifact.getMetadata();
        return metadata != null && (metadata.containsKey("conversion.sourceFormat")
                || metadata.containsKey("conversion.provider")
                || metadata.containsKey("conversion.createdAt"));
    }

    private static boolean isReaderEntry(String name) {
        return SupportedFormatRegistry.standard().detect(name)
                .filter(format -> format.family() == SupportedFormat.Family.BOOK)
                .map(SupportedFormat::readerSupported)
                .orElse(false);
    }

    private static String readerSuffix(String name) {
        if (name == null) return ".book";
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        int dot = name.lastIndexOf('.');
        return dot > slash ? name.substring(dot) : ".book";
    }

    public record SourceChoice(Book book, Path container, String artifactId, boolean fallbackFromPreferred) { }
    public record MaterializedSource(Path readerPath, Path temporaryPath) { }
}
