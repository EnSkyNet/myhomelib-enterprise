package com.myhomelibcorp.application.usecase.export;

import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.application.dto.InpxExportRequest;
import com.myhomelibcorp.application.catalog.collectioninfo.CollectionInfoCodec;
import com.myhomelibcorp.application.catalog.collectioninfo.CollectionSourceProperties;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Iterator;
import java.util.List;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExportToInpxUseCase {

    private static final char FIELD_DELIMITER = 4;
    private static final String ITEM_DELIMITER = ":";
    private static final String SUBITEM_DELIMITER = ",";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");
    private static final DateTimeFormatter VERSION_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMdd");
    private static final String STRUCTURE =
            "AUTHOR;GENRE;TITLE;SERIES;SERNO;FILE;SIZE;LIBID;DEL;EXT;DATE;INSNO;FOLDER;LANG;KEYWORDS;";

    private final BookQueryRepository bookQueryRepository;
    private final CollectionLifecyclePort collectionLifecyclePort;
    private final LibraryOperationCoordinator operationCoordinator;

    /** Stable UI-facing result. Display text is resolved only in the UI language catalog. */
    public record ExportResult(int exported, int failed, String errorKey, List<String> errorArguments, String technicalDetail) {
        public ExportResult {
            errorArguments = errorArguments == null ? List.of() : List.copyOf(errorArguments);
            technicalDetail = technicalDetail == null ? "" : technicalDetail;
        }

        public static ExportResult success(int count) {
            return new ExportResult(count, 0, null, List.of(), "");
        }

        public static ExportResult partial(int exported, int failed) {
            return new ExportResult(exported, failed, "ui.export.inpx.partial",
                    List.of(String.valueOf(exported), String.valueOf(failed)), "");
        }

        public static ExportResult failure(String key) {
            return new ExportResult(0, 1, key, List.of(), "");
        }

        public static ExportResult failure(String key, Throwable error) {
            String technical = error == null ? "" : (error.getMessage() == null || error.getMessage().isBlank()
                    ? error.getClass().getName() : error.getMessage());
            return new ExportResult(0, 1, key, List.of(), technical);
        }
    }

    private record ExportCounts(int exported, int failed) { }

    public ExportResult execute(InpxExportRequest request) {
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.EXPORT);
        try {
            ExportResult result = executeLocked(request);
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private ExportResult executeLocked(InpxExportRequest request) {
        if (request == null) {
            return ExportResult.failure("ui.export.inpx.error.no_request");
        }
        if (request.getOutputFile() == null) {
            return ExportResult.failure("ui.export.inpx.error.no_output");
        }
        requireActiveCollection(request.getCollectionId());

        log.info("Початок експорту в INPX: {}", request.getOutputFile());

        try (Stream<Book> books = openBookStream(request)) {
            Iterator<Book> iterator = books.iterator();
            if (!iterator.hasNext()) {
                log.warn("Немає книг для експорту");
                return ExportResult.failure("ui.export.inpx.error.no_books");
            }

            Path outputFile = request.getOutputFile().toAbsolutePath().normalize();
            Path parent = outputFile.getParent();
            if (parent == null) {
                return ExportResult.failure("ui.export.inpx.error.generic",
                        new IOException("INPX output has no parent directory"));
            }
            SafeFilePublication.createDirectoriesNoLinks(parent, "INPX export directory");
            String originalTargetHash = SafeFilePublication.snapshotExistingTarget(outputFile, "INPX export destination");

            ExportCounts counts;
            Path staging = Files.createTempFile(parent, ".mhl-inpx-", ".part");
            boolean published = false;
            try {
                try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(staging,
                        StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS))) {
                    zos.putNextEntry(new ZipEntry("books.inp"));
                    counts = exportBooks(iterator, zos);
                    zos.closeEntry();

                    zos.putNextEntry(new ZipEntry("version.info"));
                    String version = request.getCollectionVersion() != null && !request.getCollectionVersion().isBlank()
                            ? request.getCollectionVersion().trim()
                            : LocalDateTime.now().format(VERSION_FORMATTER);
                    zos.write(version.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();

                    zos.putNextEntry(new ZipEntry("structure.info"));
                    zos.write(STRUCTURE.getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();

                    zos.putNextEntry(new ZipEntry("collection.info"));
                    zos.write(buildCollectionInfo(request).getBytes(StandardCharsets.UTF_8));
                    zos.closeEntry();
                }
                SafeFilePublication.publishReplacingIfUnchanged(
                        staging, outputFile, originalTargetHash, "INPX export");
                published = true;
            } finally {
                if (!published) Files.deleteIfExists(staging);
            }

            log.info("Експорт в INPX завершено: {}, успішно={}, помилок={}",
                    outputFile, counts.exported(), counts.failed());
            return counts.failed() == 0
                    ? ExportResult.success(counts.exported())
                    : ExportResult.partial(counts.exported(), counts.failed());
        } catch (Exception e) {
            log.error("Помилка експорту в INPX", e);
            return ExportResult.failure("ui.export.inpx.error.generic", e);
        }
    }

    private void requireActiveCollection(String expectedCollectionId) {
        if (expectedCollectionId == null || expectedCollectionId.isBlank()) {
            throw new IllegalArgumentException("INPX export request is missing collectionId");
        }
        var active = collectionLifecyclePort.getCurrentCollection();
        if (active == null || active.getId() == null || !expectedCollectionId.equals(active.getId())) {
            throw new IllegalStateException("Active collection changed before INPX export started");
        }
    }

    private Stream<Book> openBookStream(InpxExportRequest request) {
        if (request.getBookIds() != null && !request.getBookIds().isEmpty()) {
            return bookQueryRepository.findByIds(request.getBookIds()).stream();
        }
        // streamAll() traverses the entire catalog page-by-page and avoids the legacy 10,000-row cap.
        return bookQueryRepository.streamAll();
    }

    private ExportCounts exportBooks(Iterator<Book> books, ZipOutputStream zos) throws IOException {
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(zos, StandardCharsets.UTF_8));
        int exported = 0;
        int failed = 0;

        while (books.hasNext()) {
            Book book = books.next();
            try {
                writer.write(buildBookLine(book));
                writer.newLine();
                exported++;
            } catch (Exception e) {
                failed++;
                log.error("Помилка експорту книги: {}", book != null ? book.getId() : null, e);
            }
        }

        writer.flush();
        return new ExportCounts(exported, failed);
    }

    private String buildBookLine(Book book) {
        StringBuilder sb = new StringBuilder();

        sb.append(buildAuthors(book)).append(FIELD_DELIMITER);
        sb.append(buildGenres(book)).append(FIELD_DELIMITER);
        sb.append(escapeField(book.getTitle())).append(FIELD_DELIMITER);
        sb.append(book.getSeries() != null ? escapeField(book.getSeries()) : "").append(FIELD_DELIMITER);
        sb.append(book.getSequenceNumber() != null ? book.getSequenceNumber() : 0).append(FIELD_DELIMITER);
        sb.append(escapeField(book.getFileName())).append(FIELD_DELIMITER);
        sb.append(book.getFileSize()).append(FIELD_DELIMITER);
        sb.append(book.getId().asString()).append(FIELD_DELIMITER);
        sb.append(book.isDeleted() ? 1 : 0).append(FIELD_DELIMITER);

        String ext = book.getFileName() != null && book.getFileName().contains(".")
                ? book.getFileName().substring(book.getFileName().lastIndexOf('.') + 1)
                : "";
        sb.append(ext).append(FIELD_DELIMITER);

        // Preserve absence of source metadata. Inventing today's date would make exports
        // nondeterministic and would incorrectly turn "unknown" into a real catalog date.
        String date = book.getUpdateDate() != null
                ? book.getUpdateDate().format(DATE_FORMATTER)
                : "";
        sb.append(date).append(FIELD_DELIMITER);

        sb.append(0).append(FIELD_DELIMITER);
        sb.append(book.getFolder() != null ? escapeField(book.getFolder()) : "").append(FIELD_DELIMITER);
        sb.append(book.getLanguage() != null ? book.getLanguage().toString() : "und").append(FIELD_DELIMITER);
        sb.append(book.getKeywords() != null ? escapeField(book.getKeywords()) : "");
        return sb.toString();
    }

    private String buildAuthors(Book book) {
        if (book.getAuthors().isEmpty()) {
            // INPX supports an empty AUTHOR field. Do not persist a localized sentinel as data.
            return "";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < book.getAuthors().size(); i++) {
            var author = book.getAuthors().get(i);
            if (i > 0) sb.append(ITEM_DELIMITER);
            sb.append(author.getLastName())
                    .append(SUBITEM_DELIMITER)
                    .append(author.getFirstName() != null ? author.getFirstName() : "")
                    .append(SUBITEM_DELIMITER)
                    .append(author.getMiddleName() != null ? author.getMiddleName() : "");
        }
        return sb.toString();
    }

    private String buildGenres(Book book) {
        if (book.getGenres().isEmpty()) {
            return "0.0";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < book.getGenres().size(); i++) {
            var genre = book.getGenres().get(i);
            if (i > 0) sb.append(ITEM_DELIMITER);
            sb.append(genre.getId().asString());
        }
        return sb.toString();
    }

    private String buildCollectionInfo(InpxExportRequest request) {
        return CollectionInfoCodec.serialize(new CollectionSourceProperties(
                request.getCollectionName() != null ? request.getCollectionName() : "MyHomeLib Collection",
                request.getOutputFile().getFileName().toString(),
                request.getCollectionType() == null ? 0 : request.getCollectionType(),
                request.getCollectionNotes(),
                request.getCollectionUrl(),
                request.getConnectionScript()));
    }

    private String escapeField(String value) {
        if (value == null) return "";
        return value.replace("\n", " ").replace("\r", " ").trim();
    }
}
