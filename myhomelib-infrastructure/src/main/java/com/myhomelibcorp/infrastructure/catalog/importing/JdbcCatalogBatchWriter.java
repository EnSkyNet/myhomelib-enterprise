package com.myhomelibcorp.infrastructure.catalog.importing;

import com.myhomelibcorp.application.catalog.importing.CatalogArtifact;
import com.myhomelibcorp.application.catalog.importing.CatalogDatasetInfo;
import com.myhomelibcorp.application.catalog.importing.CatalogPerson;
import com.myhomelibcorp.application.catalog.importing.CatalogRecord;
import com.myhomelibcorp.application.catalog.importing.ExternalIdentity;
import com.myhomelibcorp.application.imports.context.ImportContext;
import com.myhomelibcorp.application.imports.statistics.ImportChangeAccumulator;
import com.myhomelibcorp.domain.service.LanguageResolver;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.AuthorSearchNameNormalizer;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Bounded SQL persistence for one neutral-catalog batch.
 *
 * <p>This class owns record-to-relational persistence only; stream orchestration, transaction
 * boundaries and manifest lifecycle stay in {@link JdbcCatalogImportAdapter}.</p>
 */
final class JdbcCatalogBatchWriter {
    private static final int SQLITE_QUERY_CHUNK = 400;

    void persistBatch(
            JdbcTemplate jdbc,
            List<CatalogRecord> records,
            String sourceId,
            String sourceKey,
            ImportContext context,
            boolean fullSnapshot,
            ImportChangeAccumulator changes) {

        List<RecordState> states = new ArrayList<>(records.size());
        List<String> bookIds = new ArrayList<>(records.size());
        for (CatalogRecord record : records) {
            String id = stableBookId(record, sourceId);
            states.add(new RecordState(record, id));
            bookIds.add(id);
        }

        Map<String, Integer> existingDeleted = existingBookStates(jdbc, bookIds);
        if (!fullSnapshot) {
            for (RecordState state : states) {
                boolean exists = existingDeleted.containsKey(state.bookId);
                if (state.record.deleted()) {
                    if (exists) changes.recordDeleted(state.bookId);
                    else changes.markUnchanged(state.bookId);
                } else if (!exists) {
                    changes.recordInserted(state.bookId);
                } else {
                    changes.recordUpdated(state.bookId);
                }
            }
        }

        Map<PersonKey, String> authorIds = resolveAuthors(jdbc, states, sourceId);
        Set<String> genreCodes = new LinkedHashSet<>();
        for (RecordState state : states) genreCodes.addAll(state.record.genres());
        persistGenres(jdbc, genreCodes);

        String upsertBook = """
                INSERT INTO books (
                    id,title,series,sequence_number,file_name,folder,archive_entry,language,file_size,
                    keywords,annotation,rate,progress,update_date,isbn,deleted,local,review,created_at,
                    collection_root,year,publisher,lib_id,library_rate,translators,city,source_url,format,author_sort
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET
                    title=excluded.title,
                    series=excluded.series,
                    sequence_number=excluded.sequence_number,
                    file_name=CASE WHEN books.local=1 THEN books.file_name ELSE excluded.file_name END,
                    folder=CASE WHEN books.local=1 THEN books.folder ELSE excluded.folder END,
                    archive_entry=CASE WHEN books.local=1 THEN books.archive_entry ELSE excluded.archive_entry END,
                    language=excluded.language,
                    file_size=CASE WHEN books.local=1 THEN books.file_size ELSE excluded.file_size END,
                    keywords=excluded.keywords,
                    annotation=excluded.annotation,
                    rate=books.rate,
                    progress=books.progress,
                    update_date=excluded.update_date,
                    isbn=excluded.isbn,
                    deleted=excluded.deleted,
                    local=CASE WHEN books.local=1 THEN 1 ELSE excluded.local END,
                    review=books.review,
                    created_at=books.created_at,
                    collection_root=CASE WHEN books.local=1 THEN books.collection_root ELSE excluded.collection_root END,
                    year=excluded.year,
                    publisher=excluded.publisher,
                    lib_id=CASE WHEN COALESCE(excluded.lib_id,'')<>'' THEN excluded.lib_id ELSE books.lib_id END,
                    library_rate=excluded.library_rate,
                    translators=excluded.translators,
                    city=excluded.city,
                    source_url=CASE WHEN COALESCE(excluded.source_url,'')<>'' THEN excluded.source_url ELSE books.source_url END,
                    format=CASE WHEN books.local=1 THEN books.format ELSE excluded.format END,
                    author_sort=excluded.author_sort
                """;

        String now = Instant.now().toString();
        List<Object[]> books = new ArrayList<>(states.size());
        for (RecordState state : states) {
            CatalogRecord r = state.record;
            String archive = normalizeRelative(r.archive());
            String archiveEntry = normalizeRelative(r.archiveEntry());
            String fileName = firstNonBlank(r.fileName(), r.artifacts().isEmpty() ? "" : r.artifacts().getFirst().name(), "unknown");
            String folder = archive;
            String language = LanguageResolver.resolveValue(r.language());
            String libId = flibustaBookId(r);
            int libraryRate = r.rating() == null ? 0 : (int) Math.round(r.rating());
            String root = context.getRootDirectory() == null ? "" : context.getRootDirectory().toAbsolutePath().normalize().toString();
            books.add(new Object[]{
                    state.bookId, firstNonBlank(r.title(), "Без назви"), blankToNull(r.series()),
                    r.sequence() == null ? 0 : r.sequence().intValue(), fileName, folder, archiveEntry,
                    language, r.size() == null ? 0L : Math.max(0L, r.size()), String.join(",", r.keywords()),
                    blankToNull(r.annotation()), 0, 0, now, blankToNull(r.isbn()), r.deleted() ? 1 : 0,
                    0, "", now, root, r.publicationYear(), blankToNull(r.publisher()), blankToNull(libId),
                    libraryRate, translators(r.translators()), blankToNull(r.publicationCity()),
                    firstNonBlank(context.getCatalogSourceLocation(), sourceKey), normalizeFormat(r.fileFormat(), fileName),
                    authorSort(r.authors())
            });
        }
        jdbc.batchUpdate(upsertBook, books);

        deleteRelations(jdbc, bookIds);
        persistBookAuthors(jdbc, states, authorIds);
        persistBookGenres(jdbc, states);
        persistBookIdentities(jdbc, states, sourceId);
        persistArtifacts(jdbc, states, sourceId);
        persistRecordProvenance(jdbc, states, sourceId);
        persistSourceRelations(jdbc, states, sourceId);

        if (fullSnapshot) {
            jdbc.batchUpdate("INSERT OR IGNORE INTO temp_catalog_seen_v7(book_id) VALUES (?)",
                    bookIds.stream().map(id -> new Object[]{id}).toList());
        }
    }

    private Map<PersonKey, String> resolveAuthors(JdbcTemplate jdbc, List<RecordState> states, String sourceId) {
        LinkedHashMap<PersonKey, CatalogPerson> people = new LinkedHashMap<>();
        for (RecordState state : states) {
            for (CatalogPerson person : state.record.authors()) people.putIfAbsent(PersonKey.of(person), person);
        }
        if (people.isEmpty()) {
            CatalogPerson unknown = new CatalogPerson("", "", "Невідомий Автор", "", "Невідомий Автор", "", List.of());
            people.put(PersonKey.of(unknown), unknown);
        }

        Map<PersonKey, String> resolved = new HashMap<>();
        Map<IdentityKey, PersonKey> identityOwners = new LinkedHashMap<>();
        List<PersonKey> noIdentity = new ArrayList<>();
        for (Map.Entry<PersonKey, CatalogPerson> entry : people.entrySet()) {
            ExternalIdentity identity = preferredIdentity(entry.getValue());
            if (identity == null) noIdentity.add(entry.getKey());
            else identityOwners.put(new IdentityKey(identity.scheme(), identity.value()), entry.getKey());
        }

        // Resolve source identities in bounded SQL chunks.
        List<Map.Entry<IdentityKey, PersonKey>> idEntries = new ArrayList<>(identityOwners.entrySet());
        final int identityChunk = 300;
        for (int from = 0; from < idEntries.size(); from += identityChunk) {
            List<Map.Entry<IdentityKey, PersonKey>> part = idEntries.subList(from, Math.min(idEntries.size(), from + identityChunk));
            StringBuilder sql = new StringBuilder("SELECT author_id,scheme,external_id FROM author_identities WHERE source_id=? AND (");
            List<Object> args = new ArrayList<>(1 + part.size() * 2);
            args.add(sourceId);
            for (int i = 0; i < part.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("(scheme=? AND external_id=?)");
                args.add(part.get(i).getKey().scheme);
                args.add(part.get(i).getKey().value);
            }
            sql.append(')');
            jdbc.query(sql.toString(), rs -> {
                PersonKey owner = identityOwners.get(new IdentityKey(rs.getString("scheme"), rs.getString("external_id")));
                if (owner != null) resolved.put(owner, rs.getString("author_id"));
            }, args.toArray());
        }

        // Identity-less records may use exact name lookup, but identity-bearing authors are never merged by name.
        for (int from = 0; from < noIdentity.size(); from += SQLITE_QUERY_CHUNK) {
            List<PersonKey> part = noIdentity.subList(from, Math.min(noIdentity.size(), from + SQLITE_QUERY_CHUNK));
            StringBuilder sql = new StringBuilder("SELECT id,first_name,middle_name,last_name FROM authors WHERE ");
            List<Object> args = new ArrayList<>(part.size() * 3);
            for (int i = 0; i < part.size(); i++) {
                if (i > 0) sql.append(" OR ");
                sql.append("(first_name=? AND middle_name=? AND last_name=?)");
                PersonKey k = part.get(i);
                args.add(k.first); args.add(k.middle); args.add(k.last);
            }
            jdbc.query(sql.toString(), rs -> {
                PersonKey key = new PersonKey(safe(rs.getString("first_name")), safe(rs.getString("middle_name")), safe(rs.getString("last_name")), "", "", "", "");
                // Find the corresponding identity-less key (nickname/disambiguation are not DB lookup identity).
                for (PersonKey candidate : part) {
                    if (candidate.sameName(key)) {
                        resolved.putIfAbsent(candidate, rs.getString("id"));
                    }
                }
            }, args.toArray());
        }

        List<Object[]> newAuthors = new ArrayList<>();
        List<Object[]> newIdentities = new ArrayList<>();
        for (Map.Entry<PersonKey, CatalogPerson> entry : people.entrySet()) {
            PersonKey key = entry.getKey();
            CatalogPerson person = entry.getValue();
            if (resolved.containsKey(key)) continue;
            ExternalIdentity identity = preferredIdentity(person);
            String authorId = identity == null
                    ? uuid("author-name:" + key.first + "\u0000" + key.middle + "\u0000" + key.last)
                    : uuid("author:" + sourceId + ":" + identity.scheme() + ":" + identity.value());
            resolved.put(key, authorId);
            newAuthors.add(new Object[]{authorId, key.first, key.middle, key.last, AuthorSearchNameNormalizer.normalize(key.first, key.middle, key.last),
                    blankToNull(person.nickname()), blankToNull(person.displayName()), blankToNull(person.disambiguation())});
        }
        if (!newAuthors.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT OR IGNORE INTO authors
                        (id,first_name,middle_name,last_name,search_name,nickname,display_name,disambiguation)
                    VALUES (?,?,?,?,?,?,?,?)
                    """, newAuthors);
        }

        for (Map.Entry<PersonKey, CatalogPerson> entry : people.entrySet()) {
            String authorId = resolved.get(entry.getKey());
            for (ExternalIdentity identity : entry.getValue().identities()) {
                if (identity != null && identity.usable()) {
                    newIdentities.add(new Object[]{authorId, sourceId, identity.scheme(), identity.value()});
                }
            }
        }
        if (!newIdentities.isEmpty()) {
            jdbc.batchUpdate("""
                    INSERT OR IGNORE INTO author_identities(author_id,source_id,scheme,external_id)
                    VALUES (?,?,?,?)
                    """, newIdentities);
        }
        return resolved;
    }

    private static ExternalIdentity preferredIdentity(CatalogPerson person) {
        if (person == null) return null;
        return person.identities().stream().filter(ExternalIdentity::usable)
                .sorted(Comparator.comparing(ExternalIdentity::scheme).thenComparing(ExternalIdentity::value))
                .findFirst().orElse(null);
    }

    private static Map<String, Integer> existingBookStates(JdbcTemplate jdbc, List<String> ids) {
        Map<String, Integer> result = new HashMap<>();
        for (int from = 0; from < ids.size(); from += SQLITE_QUERY_CHUNK) {
            List<String> part = ids.subList(from, Math.min(ids.size(), from + SQLITE_QUERY_CHUNK));
            String placeholders = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
            jdbc.query("SELECT id,deleted FROM books WHERE id IN (" + placeholders + ")",
                    (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
                        result.put(rs.getString("id"), rs.getInt("deleted"));
                    }, part.toArray());
        }
        return result;
    }

    private static void persistGenres(JdbcTemplate jdbc, Set<String> genres) {
        if (genres.isEmpty()) return;
        jdbc.batchUpdate("INSERT OR IGNORE INTO genres(code,name,parent_code,fb2_code) VALUES (?,?,NULL,?)",
                genres.stream().filter(g -> g != null && !g.isBlank())
                        .map(g -> new Object[]{g.trim(), g.trim(), g.trim()}).toList());
    }

    private static void deleteRelations(JdbcTemplate jdbc, List<String> bookIds) {
        for (int from = 0; from < bookIds.size(); from += SQLITE_QUERY_CHUNK) {
            List<String> part = bookIds.subList(from, Math.min(bookIds.size(), from + SQLITE_QUERY_CHUNK));
            String placeholders = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
            jdbc.update("DELETE FROM book_authors WHERE book_id IN (" + placeholders + ")", part.toArray());
            jdbc.update("DELETE FROM book_genres WHERE book_id IN (" + placeholders + ")", part.toArray());
        }
    }

    private static void persistBookAuthors(JdbcTemplate jdbc, List<RecordState> states, Map<PersonKey, String> authorIds) {
        List<Object[]> links = new ArrayList<>();
        for (RecordState state : states) {
            List<CatalogPerson> authors = state.record.authors();
            if (authors.isEmpty()) authors = List.of(new CatalogPerson("", "", "Невідомий Автор", "", "Невідомий Автор", "", List.of()));
            for (CatalogPerson author : authors) {
                String id = authorIds.get(PersonKey.of(author));
                if (id != null) links.add(new Object[]{state.bookId, id});
            }
        }
        if (!links.isEmpty()) jdbc.batchUpdate("INSERT OR IGNORE INTO book_authors(book_id,author_id) VALUES (?,?)", links);
    }

    private static void persistBookGenres(JdbcTemplate jdbc, List<RecordState> states) {
        List<Object[]> links = new ArrayList<>();
        for (RecordState state : states) for (String genre : state.record.genres()) {
            if (genre != null && !genre.isBlank()) links.add(new Object[]{state.bookId, genre.trim()});
        }
        if (!links.isEmpty()) jdbc.batchUpdate("INSERT OR IGNORE INTO book_genres(book_id,genre_code) VALUES (?,?)", links);
    }

    private static void persistBookIdentities(JdbcTemplate jdbc, List<RecordState> states, String sourceId) {
        List<Object[]> identities = new ArrayList<>();
        for (RecordState state : states) {
            identities.add(new Object[]{state.bookId, sourceId, "catalog-record", state.record.sourceBookId()});
            for (ExternalIdentity identity : state.record.externalIdentities()) {
                if (identity != null && identity.usable()) identities.add(new Object[]{state.bookId, sourceId, identity.scheme(), identity.value()});
            }
        }
        jdbc.batchUpdate("""
                INSERT OR IGNORE INTO book_identities(book_id,source_id,scheme,external_id)
                VALUES (?,?,?,?)
                """, identities);
    }

    private static void persistArtifacts(JdbcTemplate jdbc, List<RecordState> states, String sourceId) {
        List<String> ids = states.stream().map(RecordState::bookId).toList();
        for (int from = 0; from < ids.size(); from += SQLITE_QUERY_CHUNK) {
            List<String> part = ids.subList(from, Math.min(ids.size(), from + SQLITE_QUERY_CHUNK));
            String placeholders = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
            List<Object> args = new ArrayList<>(); args.add(sourceId); args.addAll(part);
            jdbc.update("DELETE FROM book_artifacts WHERE source_id=? AND book_id IN (" + placeholders + ")", args.toArray());
        }
        List<Object[]> rows = new ArrayList<>();
        List<Object[]> metadataRows = new ArrayList<>();
        List<Object[]> occurrenceRows = new ArrayList<>();
        for (RecordState state : states) {
            int ordinal = 0;
            for (CatalogArtifact artifact : state.record.artifacts()) {
                String artifactId = uuid("artifact:" + sourceId + ":" + state.bookId + ":" + ordinal + ":"
                        + artifact.name() + ":" + artifact.sha256());
                rows.add(new Object[]{artifactId, state.bookId, sourceId,
                        firstNonBlank(artifact.name(), "artifact-" + ordinal), blankToNull(artifact.mediaType()),
                        blankToNull(artifact.fileFormat()), blankToNull(artifact.name()), blankToNull(artifact.archive()),
                        blankToNull(artifact.archiveEntry()), artifact.size(), blankToNull(artifact.sha256()),
                        blankToNull(artifact.contentFingerprint()), 1, 0});
                for (Map.Entry<String, String> metadata : artifact.metadata().entrySet()) {
                    if (metadata.getKey() != null && !metadata.getKey().isBlank()) {
                        metadataRows.add(new Object[]{artifactId, metadata.getKey(), metadata.getValue()});
                    }
                }
                int occurrenceCount = parseInt(artifact.metadata().get("occurrence.count"), 0);
                for (int i = 0; i < occurrenceCount; i++) {
                    String prefix = "occurrence." + i + ".";
                    String occurrenceArchive = safe(artifact.metadata().get(prefix + "archive"));
                    String occurrenceEntry = safe(artifact.metadata().get(prefix + "entry"));
                    if (occurrenceArchive.isBlank() || occurrenceEntry.isBlank()) continue;
                    String occurrenceId = uuid("occurrence:" + artifactId + ":" + i + ":" + occurrenceArchive + ":" + occurrenceEntry);
                    occurrenceRows.add(new Object[]{occurrenceId, artifactId, sourceId, state.bookId, i,
                            occurrenceArchive, occurrenceEntry, parseLongNullable(artifact.metadata().get(prefix + "index")),
                            parseLongNullable(artifact.metadata().get(prefix + "compressed_size")),
                            parseLongNullable(artifact.metadata().get(prefix + "uncompressed_size")),
                            blankToNull(artifact.metadata().get(prefix + "modified"))});
                }
                ordinal++;
            }
        }
        if (!rows.isEmpty()) jdbc.batchUpdate("""
                INSERT INTO book_artifacts(
                    artifact_id,book_id,source_id,artifact_name,media_type,file_format,file_name,archive_name,
                    archive_entry,size_bytes,sha256,content_fingerprint,remote,local
                ) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(artifact_id) DO UPDATE SET
                    media_type=excluded.media_type,file_format=excluded.file_format,file_name=excluded.file_name,
                    archive_name=excluded.archive_name,archive_entry=excluded.archive_entry,size_bytes=excluded.size_bytes,
                    sha256=excluded.sha256,content_fingerprint=excluded.content_fingerprint,
                    remote=excluded.remote,local=excluded.local,updated_at=CURRENT_TIMESTAMP
                """, rows);
        if (!metadataRows.isEmpty()) jdbc.batchUpdate("""
                INSERT INTO book_artifact_metadata(artifact_id,metadata_key,metadata_value) VALUES (?,?,?)
                ON CONFLICT(artifact_id,metadata_key) DO UPDATE SET metadata_value=excluded.metadata_value
                """, metadataRows);
        if (!occurrenceRows.isEmpty()) jdbc.batchUpdate("""
                INSERT INTO artifact_occurrences(
                    occurrence_id,artifact_id,source_id,book_id,occurrence_index,archive_name,entry_name,archive_index,
                    compressed_size,uncompressed_size,modified_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(occurrence_id) DO UPDATE SET
                    archive_name=excluded.archive_name,entry_name=excluded.entry_name,archive_index=excluded.archive_index,
                    compressed_size=excluded.compressed_size,uncompressed_size=excluded.uncompressed_size,
                    modified_at=excluded.modified_at
                """, occurrenceRows);
    }

    void persistDatasetMetadata(JdbcTemplate jdbc, String sourceId, CatalogDatasetInfo dataset) {
        if (dataset == null) return;
        Map<String, String> m = dataset.metadata();
        jdbc.update("""
                INSERT INTO catalog_dataset_metadata(
                    source_id,dataset_id,dataset_schema,record_schema,library,generator_name,generator_version,
                    normalization_model,database_id,database_format,dump_date,dump_checksum,database_dumps_json,
                    ordering_json,processing_json,archives_json,features_json,raw_header_json,imported_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                ON CONFLICT(source_id) DO UPDATE SET
                    dataset_id=excluded.dataset_id,dataset_schema=excluded.dataset_schema,record_schema=excluded.record_schema,
                    library=excluded.library,generator_name=excluded.generator_name,generator_version=excluded.generator_version,
                    normalization_model=excluded.normalization_model,database_id=excluded.database_id,
                    database_format=excluded.database_format,dump_date=excluded.dump_date,dump_checksum=excluded.dump_checksum,
                    database_dumps_json=excluded.database_dumps_json,ordering_json=excluded.ordering_json,
                    processing_json=excluded.processing_json,archives_json=excluded.archives_json,
                    features_json=excluded.features_json,raw_header_json=excluded.raw_header_json,
                    imported_at=CURRENT_TIMESTAMP
                """, sourceId, blankToNull(dataset.datasetId()), blankToNull(dataset.schema()),
                blankToNull(dataset.recordSchema()), blankToNull(dataset.library()),
                blankToNull(m.get("generator.name")), blankToNull(m.get("generator.version")),
                blankToNull(m.get("normalization.model")), blankToNull(m.get("database.id")),
                blankToNull(m.get("database.format")), blankToNull(m.get("database.dump_date")),
                blankToNull(m.get("database.dump_checksum")), blankToNull(m.get("database.dumps.json")),
                blankToNull(m.get("ordering.json")), blankToNull(m.get("processing.json")),
                blankToNull(m.get("archives.json")), blankToNull(m.get("features.json")),
                blankToNull(m.get("metabib.header.json")));
    }

    private static void persistRecordProvenance(JdbcTemplate jdbc, List<RecordState> states, String sourceId) {
        List<Object[]> rows = new ArrayList<>();
        for (RecordState state : states) {
            Map<String, String> m = state.record.sourceMetadata();
            String locatorValue = firstNonBlank(m.get("locator.book_id"), m.get("locator.index"));
            if (!m.containsKey("metabib.record.json") && !m.containsKey("metabib.observations.json")
                    && !m.containsKey("metabib.claims.json")) continue;
            rows.add(new Object[]{sourceId, state.bookId, blankToNull(m.get("dataset")), state.record.sourceBookId(),
                    blankToNull(m.get("metabib.record.schema")), blankToNull(m.get("locator.kind")),
                    blankToNull(m.get("locator.source")), blankToNull(locatorValue),
                    blankToNull(m.get("metabib.record.json")), blankToNull(m.get("metabib.observations.json")),
                    blankToNull(m.get("metabib.claims.json")), blankToNull(m.get("metabib.identities.json")),
                    blankToNull(m.get("metabib.artifacts.json"))});
        }
        if (!rows.isEmpty()) jdbc.batchUpdate("""
                INSERT INTO catalog_record_provenance(
                    source_id,book_id,dataset_id,source_book_id,record_schema,locator_kind,locator_source,locator_value,
                    raw_record_json,observations_json,claims_json,identities_json,artifacts_json,imported_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                ON CONFLICT(source_id,book_id) DO UPDATE SET
                    dataset_id=excluded.dataset_id,source_book_id=excluded.source_book_id,record_schema=excluded.record_schema,
                    locator_kind=excluded.locator_kind,locator_source=excluded.locator_source,locator_value=excluded.locator_value,
                    raw_record_json=excluded.raw_record_json,observations_json=excluded.observations_json,
                    claims_json=excluded.claims_json,identities_json=excluded.identities_json,
                    artifacts_json=excluded.artifacts_json,imported_at=CURRENT_TIMESTAMP
                """, rows);
    }

    private static void persistSourceRelations(JdbcTemplate jdbc, List<RecordState> states, String sourceId) {
        List<String> ids = states.stream().map(RecordState::bookId).toList();
        for (int from = 0; from < ids.size(); from += SQLITE_QUERY_CHUNK) {
            List<String> part = ids.subList(from, Math.min(ids.size(), from + SQLITE_QUERY_CHUNK));
            String placeholders = String.join(",", java.util.Collections.nCopies(part.size(), "?"));
            List<Object> args = new ArrayList<>(); args.add(sourceId); args.addAll(part);
            jdbc.update("DELETE FROM book_source_relations WHERE source_id=? AND book_id IN (" + placeholders + ")", args.toArray());
        }
        List<Object[]> rows = new ArrayList<>();
        for (RecordState state : states) {
            Map<String, String> m = state.record.sourceMetadata();
            int count = parseInt(m.get("metabib.relation.count"), 0);
            for (int i = 0; i < count; i++) {
                String prefix = "metabib.relation." + i + ".";
                String type = safe(m.get(prefix + "type")).trim();
                if (type.isBlank()) continue;
                String targetScheme = blankToNull(m.get(prefix + "target.scheme"));
                String targetValue = blankToNull(m.get(prefix + "target.value"));
                String relationId = uuid("relation:" + sourceId + ":" + state.bookId + ":" + i + ":" + type + ":"
                        + safe(targetScheme) + ":" + safe(targetValue));
                rows.add(new Object[]{relationId, sourceId, state.bookId, i, type,
                        blankToNull(m.get(prefix + "observation")), targetScheme, targetValue,
                        blankToNull(m.get(prefix + "event_id")), blankToNull(m.get(prefix + "time")),
                        blankToNull(m.get(prefix + "participants.json")), blankToNull(m.get(prefix + "raw.json"))});
            }
        }
        if (!rows.isEmpty()) jdbc.batchUpdate("""
                INSERT INTO book_source_relations(
                    relation_id,source_id,book_id,relation_index,relation_type,observation_id,target_scheme,target_value,
                    event_id,event_time,participants_json,raw_relation_json,imported_at)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,CURRENT_TIMESTAMP)
                ON CONFLICT(relation_id) DO UPDATE SET
                    relation_type=excluded.relation_type,observation_id=excluded.observation_id,
                    target_scheme=excluded.target_scheme,target_value=excluded.target_value,event_id=excluded.event_id,
                    event_time=excluded.event_time,participants_json=excluded.participants_json,
                    raw_relation_json=excluded.raw_relation_json,imported_at=CURRENT_TIMESTAMP
                """, rows);
    }

    private static String stableBookId(CatalogRecord record, String sourceId) {
        String flibustaId = flibustaBookId(record);
        if (!flibustaId.isBlank()) return uuid("inpx:libid:" + flibustaId);
        return uuid("catalog-book:" + sourceId + ":" + record.sourceBookId());
    }

    private static String flibustaBookId(CatalogRecord record) {
        String id = record.sourceMetadata().getOrDefault("locator.book_id", "").trim();
        String library = record.sourceMetadata().getOrDefault("library", "").toLowerCase(Locale.ROOT);
        if (!id.isBlank() && library.contains("flibusta")) return id;
        if (record.sourceBookId().startsWith("libid:")) return record.sourceBookId().substring("libid:".length());
        for (ExternalIdentity identity : record.externalIdentities()) {
            String scheme = identity.scheme().toLowerCase(Locale.ROOT);
            if (identity.usable() && (scheme.contains("flibusta") || scheme.endsWith(":book_id"))) return identity.value();
        }
        return "";
    }

    private static String authorSort(List<CatalogPerson> authors) {
        return authors.stream().map(JdbcCatalogBatchWriter::searchName).filter(s -> !s.isBlank())
                .map(s -> s.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim()).min(String::compareTo)
                .orElse("невідомий автор");
    }
    private static String searchName(CatalogPerson p) {
        return String.join(" ", List.of(safe(p.lastName()), safe(p.firstName()), safe(p.middleName()))
                .stream().filter(v -> !v.isBlank()).toList()).trim();
    }
    private static String translators(List<CatalogPerson> translators) {
        return String.join(", ", translators.stream().map(JdbcCatalogBatchWriter::searchName).filter(v -> !v.isBlank()).toList());
    }
    private static String normalizeFormat(String format, String fileName) {
        String f = safe(format).trim().toLowerCase(Locale.ROOT);
        if (!f.isBlank()) return f.startsWith(".") ? f.substring(1) : f;
        int dot = fileName.lastIndexOf('.');
        return dot >= 0 && dot + 1 < fileName.length() ? fileName.substring(dot + 1).toLowerCase(Locale.ROOT) : "";
    }
    private static String normalizeRelative(String value) {
        String v = safe(value).replace('\\', '/').trim();
        while (v.startsWith("/")) v = v.substring(1);
        return v.equals("..") || v.contains("../") ? "" : v;
    }
    private static int parseInt(String value, int fallback) {
        if (value == null || value.isBlank()) return fallback;
        try { return Integer.parseInt(value.trim()); } catch (NumberFormatException e) { return fallback; }
    }
    private static Long parseLongNullable(String value) {
        if (value == null || value.isBlank()) return null;
        try { return Long.parseLong(value.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String uuid(String value) { return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8)).toString(); }
    private static String safe(String value) { return value == null ? "" : value; }
    private static String blankToNull(String value) { return value == null || value.isBlank() ? null : value.trim(); }
    private static String firstNonBlank(String... values) {
        for (String v : values) if (v != null && !v.isBlank()) return v.trim();
        return "";
    }
    private record RecordState(CatalogRecord record, String bookId) { }
    private record IdentityKey(String scheme, String value) { }
    private record PersonKey(String first, String middle, String last, String nickname, String disambiguation,
                             String identityScheme, String identityValue) {
        static PersonKey of(CatalogPerson p) {
            ExternalIdentity identity = preferredIdentity(p);
            return new PersonKey(safe(p.firstName()), safe(p.middleName()), safe(p.lastName()),
                    safe(p.nickname()), safe(p.disambiguation()),
                    identity == null ? "" : safe(identity.scheme()),
                    identity == null ? "" : safe(identity.value()));
        }
        boolean sameName(PersonKey other) {
            return first.equals(other.first) && middle.equals(other.middle) && last.equals(other.last);
        }
    }
}
