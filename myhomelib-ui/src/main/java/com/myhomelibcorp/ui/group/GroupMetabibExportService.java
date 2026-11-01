package com.myhomelibcorp.ui.group;

import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.CollectionBookReadPort;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.book.BookPageCursor;
import com.myhomelibcorp.application.query.book.BookPageDirection;
import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.group.Group;
import com.myhomelibcorp.domain.model.valueobject.GroupId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Opt-in portable export of a named personal group across registered collections.
 *
 * <p>The selected group is resolved by ID in the active collection. In every other
 * registered collection the same normalized group name is resolved locally, and
 * those memberships are included in the package. Collection-local group IDs are
 * never compared across databases.</p>
 *
 * <p>Each collection is read through one isolated read-only SQLite snapshot at a
 * time. The active collection is never switched, collection databases are never
 * mutated, and file bytes are streamed into ZIP batches of at most 1,000 books.</p>
 *
 * <p>A staging folder is published only after every ZIP and JSONL are closed.
 * This service is called with an EXPORT lifecycle lease held by the UI.</p>
 */
@Service
@RequiredArgsConstructor
public class GroupMetabibExportService {
    private static final int PAGE_SIZE = 100;
    private static final int MAX_ZIP_ENTRIES = 1000;
    private static final long MAX_BOOK_BYTES = 512L * 1024 * 1024;

    private final CollectionBookReadPort collectionBooks;
    private final CollectionRepository collectionRepository;
    private final BookResourcePort resources;
    private final CollectionLifecyclePort collections;
    private final ObjectMapper mapper = new ObjectMapper();

    public record ExportResult(Path folder, int exported, int skipped) { }

    public ExportResult export(Long groupId, String collectionId, Path destinationDirectory) throws IOException {
        if (groupId == null || groupId < 1) throw new IllegalArgumentException("Valid group ID required");
        if (collectionId == null || collectionId.isBlank()) throw new IllegalArgumentException("Collection required");
        Objects.requireNonNull(destinationDirectory, "destinationDirectory");

        Collection active = requireCurrentCollection(collectionId);
        List<Collection> registered = collectionRepository.findAll();
        if (registered == null || registered.stream().noneMatch(c -> c != null && collectionId.equals(c.getId()))) {
            throw new IOException("The active collection is not present in the registered collection list");
        }
        Set<String> registeredIds = new HashSet<>();
        for (Collection item : registered) {
            if (item == null || item.getId() == null || item.getId().isBlank()
                    || !registeredIds.add(item.getId())) {
                throw new IOException("Registered collections contain a blank or duplicate collection ID");
            }
        }

        Set<String> collectionIds = new HashSet<>();
        List<Collection> sources = new ArrayList<>();
        sources.add(active);
        registered.stream()
                .filter(c -> c != null && !collectionId.equals(c.getId()))
                .sorted(Comparator
                        .comparing((Collection c) -> safe(c.getName()), String.CASE_INSENSITIVE_ORDER)
                        .thenComparing(c -> safe(c.getId())))
                .forEach(sources::add);
        for (Collection source : sources) {
            if (source.getId() == null || source.getId().isBlank() || !collectionIds.add(source.getId())) {
                throw new IOException("Registered collections contain a blank or duplicate collection ID");
            }
            if (source.getName() == null || source.getName().isBlank()) {
                throw new IOException("A registered collection has no name: " + source.getId());
            }
        }

        Path parent = destinationDirectory.toAbsolutePath().normalize();
        SafeFilePublication.createDirectoriesNoLinks(parent, "Portable group export destination");
        String packageId = "MHL-" + UUID.randomUUID();
        Path target = parent.resolve(packageId);
        Path stage = Files.createTempDirectory(parent, ".mhl-metabib-");
        boolean published = false;
        try {
            ExportResult result = build(groupId, collectionId, sources, stage, target, packageId);
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Export destination already exists");
            }
            try {
                Files.move(stage, target, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(stage, target);
            }
            published = true;
            return result;
        } finally {
            if (!published) deleteStagingTree(stage);
        }
    }

    private ExportResult build(Long activeGroupId, String activeCollectionId, List<Collection> sources,
                               Path stage, Path target, String packageId) throws IOException {
        Path spool = stage.resolve(".records.tmp");
        List<Map<String, Object>> archiveHeaders = new ArrayList<>();
        int exported = 0;
        int skipped = 0;
        String groupName = null;

        try (BufferedWriter rows = Files.newBufferedWriter(spool, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            for (Collection source : sources) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new IOException("Portable group export cancelled");
                }
                requireCurrentCollection(activeCollectionId);

                try (CollectionBookReadPort.Session session = collectionBooks.open(source)) {
                    Group sourceGroup;
                    if (activeCollectionId.equals(source.getId())) {
                        sourceGroup = session.findGroupById(activeGroupId)
                                .orElseThrow(() -> new IOException("Selected group no longer exists in the active collection"));
                        groupName = sourceGroup.getName();
                        if (groupName == null || groupName.isBlank()) {
                            throw new IOException("Selected group has no portable name");
                        }
                    } else {
                        if (groupName == null) {
                            throw new IOException("The active collection group name was not resolved");
                        }
                        Optional<Group> matching = session.findGroupByName(groupName);
                        if (matching.isEmpty()) continue;
                        sourceGroup = matching.get();
                    }

                    CollectionExportResult sourceResult = writeCollection(
                            source, sourceGroup.getId().asLong(), session, stage, archiveHeaders, rows, exported);
                    exported = sourceResult.exported();
                    skipped += sourceResult.skipped();
                } catch (IOException failure) {
                    throw new IOException("Unable to export group '" + safe(groupName)
                            + "' from collection '" + safe(source.getName()) + "': " + safe(failure.getMessage()), failure);
                } catch (IllegalStateException ambiguousGroup) {
                    throw new IOException("Cannot resolve group '" + safe(groupName)
                            + "' unambiguously in collection '" + safe(source.getName()) + "'", ambiguousGroup);
                } catch (RuntimeException failure) {
                    throw new IOException("Unable to export group '" + safe(groupName)
                            + "' from collection '" + safe(source.getName()) + "': " + safe(failure.getMessage()), failure);
                }
            }
        } catch (Exception failure) {
            throw failure instanceof IOException io ? io : new IOException("Portable group export failed", failure);
        }

        if (exported == 0) throw new IOException("No locally readable books in the selected group");
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("schema", "metabib.dataset/1");
        header.put("id", packageId);
        header.put("record_schema", "metabib.dataset_record/1");
        header.put("library", "mhl-portable");
        header.put("created", Instant.now().toString());
        header.put("records", exported);
        header.put("generator", Map.of("name", "MyHomeLib Enterprise", "version", "8.0.0"));
        header.put("normalization", Map.of("model", "metabib.norm/1"));
        header.put("ordering", Map.of("mode", "archive_entry", "direction", "ascending"));
        header.put("processing", Map.of("parse_fb2", false, "archive_content_checksum", Map.of("enabled", false)));
        header.put("archives", archiveHeaders);

        Path catalog = stage.resolve("catalog.jsonl");
        try (BufferedWriter out = Files.newBufferedWriter(catalog, StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            out.write(mapper.writeValueAsString(header));
            out.newLine();
            try (var reader = Files.newBufferedReader(spool, StandardCharsets.UTF_8)) {
                char[] buffer = new char[8192];
                int n;
                while ((n = reader.read(buffer)) >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Portable group export cancelled");
                    out.write(buffer, 0, n);
                }
            }
        }
        Files.delete(spool);
        return new ExportResult(target, exported, skipped);
    }

    private CollectionExportResult writeCollection(Collection source, Long groupId,
                                                     CollectionBookReadPort.Session session,
                                                     Path stage, List<Map<String, Object>> archiveHeaders,
                                                     BufferedWriter rows, int exportedBefore) throws IOException {
        int exported = exportedBefore;
        int skipped = 0;
        int offset = 0;
        long knownTotal = -1L;
        BookPageCursor cursor = null;
        ZipOutputStream zip = null;
        ArchiveState archive = null;
        try {
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new IOException("Portable group export cancelled");
                BookQuery query = BookQuery.builder()
                        .groupId(GroupId.fromLong(groupId))
                        .pagination(Pagination.of(PAGE_SIZE, offset))
                        .sortBy(SortBy.TITLE)
                        .direction(SortDirection.ASC)
                        .build();
                PageResultHolder page = page(session, query, cursor, knownTotal);
                if (knownTotal < 0) knownTotal = page.total();
                if (page.books().isEmpty()) break;

                for (Book book : page.books()) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("Portable group export cancelled");
                    if (book == null) { skipped++; continue; }

                    Optional<InputStream> input;
                    try {
                        input = resources.readBookData(book);
                    } catch (UncheckedIOException unreadable) {
                        if (Thread.currentThread().isInterrupted()
                                || unreadable.getCause() instanceof java.io.InterruptedIOException
                                || unreadable.getCause() instanceof java.nio.channels.ClosedByInterruptException) {
                            throw new IOException("Portable group export cancelled during source open", unreadable);
                        }
                        skipped++;
                        continue;
                    }
                    if (input.isEmpty()) { skipped++; continue; }

                    try (InputStream in = input.get()) {
                        if (zip == null || archive.entries == MAX_ZIP_ENTRIES) {
                            if (zip != null) {
                                zip.close();
                                zip = null;
                            }
                            archive = new ArchiveState(archiveHeaders.size(), source.getId(), source.getName());
                            archiveHeaders.add(archive.descriptor);
                            zip = new ZipOutputStream(Files.newOutputStream(stage.resolve(archive.zipFile),
                                    StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE));
                        }
                        String entry = String.format(java.util.Locale.ROOT, "book-%06d.%s",
                                exported + 1, extension(book));
                        zip.putNextEntry(new ZipEntry(entry));
                        long actualBytes = copyBounded(in, zip);
                        zip.closeEntry();
                        int index = archive.entries++;
                        if (entry.endsWith(".fb2")) archive.fb2Entries++;
                        archive.descriptor.put("entries", archive.entries);
                        archive.descriptor.put("fb2_entries", archive.fb2Entries);
                        rows.write(mapper.writeValueAsString(record(book, source, archive.id, entry, index, actualBytes)));
                        rows.newLine();
                        exported++;
                    }
                }

                Book last = page.books().stream()
                        .filter(Objects::nonNull)
                        .reduce((first, next) -> next)
                        .orElseThrow(() -> new IOException("Group page contains no cursor-eligible books"));
                cursor = new BookPageCursor(last.getTitle(), last.getId().asString());
                if (page.books().size() < PAGE_SIZE) break;
                if (offset > Integer.MAX_VALUE - page.books().size()) {
                    throw new IOException("Too many group entries");
                }
                offset += page.books().size();
            }
            return new CollectionExportResult(exported, skipped);
        } finally {
            if (zip != null) zip.close();
        }
    }

    private static PageResultHolder page(CollectionBookReadPort.Session session, BookQuery query,
                                         BookPageCursor cursor, long knownTotal) {
        var page = knownTotal < 0
                ? session.findPage(query)
                : session.findTitlePageByCursor(query, cursor, BookPageDirection.AFTER, knownTotal);
        return new PageResultHolder(page.content(), page.totalElements());
    }

    private Collection requireCurrentCollection(String id) {
        Collection collection = collections.getCurrentCollection();
        if (collection == null || !id.equals(collection.getId())) {
            throw new IllegalStateException("Collection changed during group export");
        }
        return collection;
    }

    private static long copyBounded(InputStream source, OutputStream target) throws IOException {
        byte[] bytes = new byte[64 * 1024];
        long total = 0;
        for (int count; (count = source.read(bytes)) != -1;) {
            if (Thread.currentThread().isInterrupted()) throw new IOException("Portable group export cancelled");
            total += count;
            if (total > MAX_BOOK_BYTES) throw new IOException("Book exceeds portable export limit");
            target.write(bytes, 0, count);
        }
        return total;
    }

    private static String extension(Book book) {
        String name = book.hasArchiveEntry() ? book.getArchiveEntry() : book.getFileName();
        int dot = name == null ? -1 : name.lastIndexOf('.');
        String ext = dot < 0 ? "bin" : name.substring(dot + 1).toLowerCase(java.util.Locale.ROOT);
        return ext.matches("[a-z0-9]{1,8}") ? ext : "bin";
    }

    private static Object claim(Object value) {
        return Map.of("observation", "db", "value", value);
    }

    private static Map<String, Object> record(Book book, Collection source, String zipId,
                                              String entryName, int index, long actualBytes) {
        Map<String, Object> bibliographic = new LinkedHashMap<>();
        bibliographic.put("title", List.of(claim(book.getTitle())));
        if (book.getLanguage() != null) bibliographic.put("language", List.of(claim(book.getLanguage().value())));
        if (book.getAnnotation() != null && !book.getAnnotation().isBlank())
            bibliographic.put("annotation", List.of(claim(book.getAnnotation())));
        List<Object> authors = new ArrayList<>();
        for (var author : book.getAuthors()) {
            authors.add(claim(Map.of(
                    "first_name", safe(author.getFirstName()),
                    "middle_name", safe(author.getMiddleName()),
                    "last_name", safe(author.getLastName()))));
        }
        bibliographic.put("authors", authors);
        if (book.getTranslators() != null && !book.getTranslators().isBlank())
            bibliographic.put("translators", List.of(claim(Map.of("display_name", book.getTranslators()))));
        if (book.getKeywords() != null && !book.getKeywords().isBlank()) {
            List<Object> keywords = new ArrayList<>();
            for (String keyword : book.getKeywords().split(",")) {
                if (!keyword.isBlank()) keywords.add(claim(keyword.trim()));
            }
            if (!keywords.isEmpty()) bibliographic.put("keywords", keywords);
        }
        List<Object> genres = new ArrayList<>();
        for (var genre : book.getGenres()) {
            String code = genre.getFb2Code();
            if (code != null && !code.isBlank()) genres.add(claim(Map.of("code", code)));
        }
        bibliographic.put("genres", genres);
        if (book.getSeries() != null && !book.getSeries().isBlank()) {
            Map<String, Object> seq = new LinkedHashMap<>();
            seq.put("name", book.getSeries());
            if (book.getSequenceNumber() != null) seq.put("number", Map.of("value", book.getSequenceNumber()));
            bibliographic.put("sequences", List.of(claim(seq)));
        }
        Map<String, Object> publication = new LinkedHashMap<>();
        if (book.getYear() != null) publication.put("year", List.of(claim(Map.of("value", book.getYear()))));
        if (book.getPublisher() != null && !book.getPublisher().isBlank())
            publication.put("publisher", List.of(claim(book.getPublisher())));
        if (book.getCity() != null && !book.getCity().isBlank())
            publication.put("city", List.of(claim(book.getCity())));
        if (book.getIsbn() != null)
            publication.put("isbn", List.of(claim(book.getIsbn().value())));
        Map<String, Object> catalog = new LinkedHashMap<>();
        if (book.getLibraryRate() > 0) catalog.put("rating", List.of(claim(book.getLibraryRate())));
        if (book.isDeleted()) catalog.put("deleted", List.of(claim("deleted")));
        List<Object> identities = new ArrayList<>();
        identities.add(Map.of("scheme", "mhl-source-book", "value", book.getId().asString(), "observation", "db"));
        if (book.getLibId() != null && book.getLibId().matches("[0-9]{1,18}")) {
            identities.add(Map.of("scheme", "mhl-source-libid", "value", book.getLibId(), "observation", "db"));
        }
        String media = switch (extension(book)) {
            case "fb2" -> "application/fb2+xml";
            case "epub" -> "application/epub+zip";
            case "pdf" -> "application/pdf";
            case "txt" -> "text/plain";
            case "zip" -> "application/zip";
            default -> "application/octet-stream";
        };
        Map<String, Object> artifact = Map.of("name", entryName, "media_type", media,
                "size", List.of(claim(actualBytes)),
                "occurrences", List.of(Map.of("archive", zipId, "entry", entryName, "index", index)));
        Map<String, Object> locator = new LinkedHashMap<>();
        locator.put("kind", "archive_entry");
        locator.put("source", zipId);
        locator.put("index", index);
        locator.put("collection_id", source.getId());
        Map<String, Object> databaseObservation = Map.of(
                "id", "db", "source", source.getId(), "kind", "database_book", "status", "present");
        Map<String, Object> archiveObservation = Map.of(
                "id", "archive", "source", zipId, "kind", "archive_entry", "status", "present",
                "locator", Map.of("entry", entryName, "index", index));
        return Map.of(
                "schema", "metabib.dataset_record/1",
                "record", Map.of("library", source.getName(), "locator", locator),
                "identities", Map.of("catalog", identities),
                "artifacts", List.of(artifact),
                "observations", List.of(databaseObservation, archiveObservation),
                "claims", Map.of("bibliographic", bibliographic, "publication", publication, "catalog", catalog),
                "issues", List.of());
    }

    private static String safe(String value) { return value == null ? "" : value; }

    private static void deleteStagingTree(Path stage) throws IOException {
        if (!Files.exists(stage, LinkOption.NOFOLLOW_LINKS)) return;
        Files.walkFileTree(stage, new java.nio.file.SimpleFileVisitor<>() {
            @Override public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
            @Override public java.nio.file.FileVisitResult postVisitDirectory(Path dir, IOException error)
                    throws IOException {
                if (error != null) throw error;
                Files.delete(dir);
                return java.nio.file.FileVisitResult.CONTINUE;
            }
        });
    }

    private record PageResultHolder(List<Book> books, long total) { }
    private record CollectionExportResult(int exported, int skipped) { }

    private static final class ArchiveState {
        final String id;
        final String zipFile;
        final Map<String, Object> descriptor = new LinkedHashMap<>();
        int entries;
        int fb2Entries;

        ArchiveState(int ordinal, String collectionId, String collectionName) {
            this.id = String.format(java.util.Locale.ROOT, "zip-%04d", ordinal + 1);
            this.zipFile = String.format(java.util.Locale.ROOT, "books-%04d.zip", ordinal + 1);
            descriptor.put("id", id);
            descriptor.put("name", zipFile);
            descriptor.put("ordinal", ordinal);
            descriptor.put("collection_id", collectionId);
            descriptor.put("collection_name", collectionName);
            descriptor.put("entries", 0);
            descriptor.put("fb2_entries", 0);
        }
    }
}
