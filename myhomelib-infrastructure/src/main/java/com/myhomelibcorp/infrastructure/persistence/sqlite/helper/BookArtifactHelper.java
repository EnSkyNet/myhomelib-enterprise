package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookArtifact;
import com.myhomelibcorp.domain.model.book.BookArtifactState;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.*;

/** Batched hydrator for the v7.2 logical-book / physical-artifact relation. */
@Component
@RequiredArgsConstructor
@Slf4j
public class BookArtifactHelper {

    private final CollectionManager collectionManager;

    private JdbcTemplate jdbc() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    /**
     * Returns books in the same order, enriched with all artifacts and their preferred selection.
     * A legacy in-memory artifact is synthesized only as a compatibility safety net for databases
     * that have not yet received the V50 backfill.
     */
    public List<Book> attachArtifacts(List<Book> books) {
        if (books == null || books.isEmpty()) return books == null ? List.of() : books;

        List<String> ids = books.stream().map(b -> b.getId().asString()).toList();
        Map<String, List<ArtifactRow>> rowsByBook = new HashMap<>();
        Map<String, String> preferredByBook = new HashMap<>();
        Map<String, Map<String, String>> metadataByArtifact = new HashMap<>();

        SqliteInClauseSupport.forEachChunk(ids, part -> {
            String placeholders = SqliteInClauseSupport.placeholders(part.size());
            jdbc().query("""
                    SELECT artifact_id, book_id, source_id, artifact_name, media_type, file_format,
                           file_name, archive_name, archive_entry, size_bytes, sha256,
                           content_fingerprint, remote, local, collection_root, folder, state
                      FROM book_artifacts
                     WHERE book_id IN (""" + placeholders + ") ORDER BY book_id, artifact_id", rs -> {
                ArtifactRow row = new ArtifactRow(
                        rs.getString("artifact_id"), rs.getString("book_id"), rs.getString("source_id"),
                        rs.getString("artifact_name"), rs.getString("media_type"), rs.getString("file_format"),
                        rs.getString("file_name"), rs.getString("archive_name"), rs.getString("archive_entry"),
                        nullableLong(rs, "size_bytes"), rs.getString("sha256"), rs.getString("content_fingerprint"),
                        rs.getInt("remote") == 1, rs.getInt("local") == 1,
                        rs.getString("collection_root"), rs.getString("folder"), rs.getString("state"));
                rowsByBook.computeIfAbsent(row.bookId(), ignored -> new ArrayList<>()).add(row);
            }, part.toArray());

            jdbc().query("""
                    SELECT book_id, preferred_artifact_id
                      FROM book_artifact_preferences
                     WHERE book_id IN (""" + placeholders + ")", rs -> {
                    preferredByBook.put(rs.getString("book_id"), rs.getString("preferred_artifact_id"));
                }, part.toArray());
        });

        List<String> artifactIds = rowsByBook.values().stream().flatMap(Collection::stream)
                .map(ArtifactRow::artifactId).toList();
        SqliteInClauseSupport.forEachChunk(artifactIds, part -> {
            String placeholders = SqliteInClauseSupport.placeholders(part.size());
            jdbc().query("""
                    SELECT artifact_id, metadata_key, metadata_value
                      FROM book_artifact_metadata
                     WHERE artifact_id IN (""" + placeholders + ") ORDER BY artifact_id, metadata_key", rs -> {
                    metadataByArtifact.computeIfAbsent(rs.getString("artifact_id"), ignored -> new LinkedHashMap<>())
                            .put(rs.getString("metadata_key"), rs.getString("metadata_value"));
                }, part.toArray());
        });

        List<Book> enriched = new ArrayList<>(books.size());
        for (Book book : books) {
            List<ArtifactRow> rows = rowsByBook.getOrDefault(book.getId().asString(), List.of());
            List<BookArtifact> artifacts = rows.stream()
                    .map(row -> toDomain(row, metadataByArtifact.getOrDefault(row.artifactId(), Map.of())))
                    .toList();
            if (artifacts.isEmpty()) artifacts = List.of(legacyArtifact(book));
            enriched.add(book.withArtifacts(artifacts, preferredByBook.get(book.getId().asString())));
        }
        log.debug("Завантажено artifacts для {} книг ({} artifact rows)", books.size(), artifactIds.size());
        return enriched;
    }

    private static BookArtifact toDomain(ArtifactRow row, Map<String, String> metadata) {
        String physicalFile = firstNonBlank(row.archiveName(), row.fileName());
        BookFile file = new BookFile(
                value(physicalFile), value(row.folder()), value(row.archiveEntry()),
                row.sizeBytes() == null ? 0L : Math.max(0L, row.sizeBytes()), value(row.collectionRoot()));
        return BookArtifact.builder()
                .id(row.artifactId())
                .sourceId(row.sourceId())
                .name(row.artifactName())
                .mediaType(row.mediaType())
                .format(row.fileFormat())
                .file(file)
                .sha256(row.sha256())
                .contentFingerprint(row.contentFingerprint())
                .remote(row.remote())
                .local(row.local())
                .state(BookArtifactState.fromStorage(row.state(), row.local(), row.remote()))
                .metadata(metadata)
                .build();
    }

    private static BookArtifact legacyArtifact(Book book) {
        return BookArtifact.builder()
                .id("legacy:" + book.getId().asString())
                .name(firstNonBlank(book.getArchiveEntry(), book.getFileName(), "book-" + book.getId().asString()))
                .file(book.getFile())
                .remote(false)
                .local(book.isLocal())
                .state(book.isLocal() ? BookArtifactState.AVAILABLE : BookArtifactState.MISSING)
                .build();
    }

    private static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static String firstNonBlank(String... values) {
        if (values != null) for (String value : values) if (value != null && !value.isBlank()) return value;
        return "";
    }

    private static String value(String value) { return value == null ? "" : value; }

    private record ArtifactRow(
            String artifactId,
            String bookId,
            String sourceId,
            String artifactName,
            String mediaType,
            String fileFormat,
            String fileName,
            String archiveName,
            String archiveEntry,
            Long sizeBytes,
            String sha256,
            String contentFingerprint,
            boolean remote,
            boolean local,
            String collectionRoot,
            String folder,
            String state
    ) {}
}
