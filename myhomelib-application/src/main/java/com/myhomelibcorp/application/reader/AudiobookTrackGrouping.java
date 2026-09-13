package com.myhomelibcorp.application.reader;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.valueobject.BookFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

/** Explicit multi-file audiobook grouping; untagged alternate audio representations stay separate. */
public final class AudiobookTrackGrouping {
    private AudiobookTrackGrouping() { }

    public static List<Path> resolve(Book book, Path primaryPath, Function<Book, Optional<Path>> locator) {
        if (book == null || primaryPath == null || locator == null) return primaryPath == null ? List.of() : List.of(primaryPath);
        BookArtifact primary = book.getArtifacts().stream().filter(a -> sameFileIdentity(book.getFile(), a)).findFirst().orElse(null);
        if (primary == null) return List.of(primaryPath);
        String group = primary.getMetadata().getOrDefault("audiobookGroup", "").trim();
        if (group.isBlank()) return List.of(primaryPath);

        List<Item> candidates = new ArrayList<>();
        for (BookArtifact artifact : book.getArtifacts()) {
            if (!artifact.isLocal() || artifact.getFile() == null || artifact.getFile().hasArchiveEntry()) continue;
            if (!isAudio(artifact.getFormat())) continue;
            if (!group.equals(artifact.getMetadata().getOrDefault("audiobookGroup", "").trim())) continue;
            Path path;
            if (artifact.getId().equals(primary.getId())) path = primaryPath;
            else {
                try { path = locator.apply(book.selectPreferredArtifact(artifact.getId())).orElse(null); }
                catch (RuntimeException ignored) { path = null; }
            }
            if (path != null && Files.isRegularFile(path)) candidates.add(new Item(artifact, path));
        }
        if (candidates.isEmpty()) return List.of(primaryPath);
        candidates.sort(Comparator.comparingInt((Item i) -> trackNumber(i.artifact())).thenComparing(i -> i.path().toString()));
        return candidates.stream().map(Item::path).distinct().toList();
    }

    private static boolean isAudio(String format) { return "mp3".equalsIgnoreCase(format) || "m4b".equalsIgnoreCase(format); }
    private static int trackNumber(BookArtifact artifact) {
        String value = artifact.getMetadata().get("trackNumber");
        try { return value == null ? Integer.MAX_VALUE : Math.max(0, Integer.parseInt(value.trim())); }
        catch (RuntimeException ignored) { return Integer.MAX_VALUE; }
    }
    private static boolean sameFileIdentity(BookFile opened, BookArtifact artifact) {
        if (opened == null || artifact == null || artifact.getFile() == null) return false;
        BookFile candidate = artifact.getFile();
        return Objects.equals(norm(opened.getFileName()), norm(candidate.getFileName()))
                && Objects.equals(norm(opened.getFolder()), norm(candidate.getFolder()))
                && Objects.equals(norm(opened.getCollectionRoot()), norm(candidate.getCollectionRoot()))
                && Objects.equals(norm(opened.getArchiveEntry()), norm(candidate.getArchiveEntry()));
    }
    private static String norm(String value) { return value == null ? "" : value.trim().replace('\\', '/'); }
    private record Item(BookArtifact artifact, Path path) { }
}
