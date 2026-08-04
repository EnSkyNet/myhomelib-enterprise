package com.myhomelibcorp.application.usecase.export;

import com.myhomelibcorp.application.dto.InpxExportRequest;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExportToInpxUseCase {

    private final BookQueryRepository bookQueryRepository;

    private static final char FIELD_DELIMITER = 4;
    private static final String ITEM_DELIMITER = ":";
    private static final String SUBITEM_DELIMITER = ",";
    private static final DateTimeFormatter DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    public record ExportResult(int exported, int failed, String error) {
        public static ExportResult success(int count) {
            return new ExportResult(count, 0, null);
        }

        public static ExportResult failure(String error) {
            return new ExportResult(0, 1, error);
        }
    }

    public ExportResult execute(InpxExportRequest request) {
        log.info("Початок експорту в INPX: {}", request.getOutputFile());

        try {
            // Отримуємо книги
            List<Book> books;
            if (request.getBookIds() != null && !request.getBookIds().isEmpty()) {
                books = bookQueryRepository.findByIds(request.getBookIds());
            } else {
                books = bookQueryRepository.findAll();
            }

            if (books.isEmpty()) {
                log.warn("Немає книг для експорту");
                return ExportResult.failure("Немає книг для експорту");
            }

            log.info("Експортується {} книг", books.size());

            // Створюємо INPX файл
            Path outputFile = request.getOutputFile();
            Files.createDirectories(outputFile.getParent());

            try (ZipOutputStream zos = new ZipOutputStream(Files.newOutputStream(outputFile))) {
                // 1. Основний файл books.inp
                zos.putNextEntry(new ZipEntry("books.inp"));
                exportBooks(books, zos, request.isIncludeExtraData());
                zos.closeEntry();

                // 2. Файл версії
                zos.putNextEntry(new ZipEntry("version.info"));
                String version = request.getCollectionVersion() != null ?
                        request.getCollectionVersion() :
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
                zos.write(version.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                // 3. Файл структури
                zos.putNextEntry(new ZipEntry("structure.info"));
                String structure = "AUTHOR;GENRE;TITLE;SERIES;SERNO;FILE;SIZE;LIBID;DEL;EXT;DATE;INSNO;FOLDER;LANG;KEYWORDS;";
                zos.write(structure.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();

                // 4. Файл інформації про колекцію
                zos.putNextEntry(new ZipEntry("collection.info"));
                String collectionInfo = buildCollectionInfo(request);
                zos.write(collectionInfo.getBytes(StandardCharsets.UTF_8));
                zos.closeEntry();
            }

            log.info("Експорт в INPX завершено: {}", outputFile);
            return ExportResult.success(books.size());

        } catch (Exception e) {
            log.error("Помилка експорту в INPX", e);
            return ExportResult.failure(e.getMessage());
        }
    }

    private void exportBooks(List<Book> books, ZipOutputStream zos, boolean includeExtraData) throws IOException {
        BufferedWriter writer = new BufferedWriter(new java.io.OutputStreamWriter(zos, StandardCharsets.UTF_8));

        for (Book book : books) {
            try {
                String line = buildBookLine(book, includeExtraData);
                writer.write(line);
                writer.newLine();
            } catch (Exception e) {
                log.error("Помилка експорту книги: {}", book.getId(), e);
            }
        }

        writer.flush();
    }

    private String buildBookLine(Book book, boolean includeExtraData) {
        StringBuilder sb = new StringBuilder();

        // AUTHOR
        sb.append(buildAuthors(book)).append(FIELD_DELIMITER);

        // GENRE
        sb.append(buildGenres(book)).append(FIELD_DELIMITER);

        // TITLE
        sb.append(escapeField(book.getTitle())).append(FIELD_DELIMITER);

        // SERIES
        sb.append(book.getSeries() != null ? escapeField(book.getSeries()) : "").append(FIELD_DELIMITER);

        // SERNO
        sb.append(book.getSequenceNumber() != null ? book.getSequenceNumber() : 0).append(FIELD_DELIMITER);

        // FILE
        sb.append(escapeField(book.getFileName())).append(FIELD_DELIMITER);

        // SIZE
        sb.append(book.getFileSize()).append(FIELD_DELIMITER);

        // LIBID
        sb.append(book.getId().asString()).append(FIELD_DELIMITER);

        // DEL
        sb.append(book.isDeleted() ? 1 : 0).append(FIELD_DELIMITER);

        // EXT
        String ext = book.getFileName() != null && book.getFileName().contains(".") ?
                book.getFileName().substring(book.getFileName().lastIndexOf('.') + 1) : "";
        sb.append(ext).append(FIELD_DELIMITER);

        // DATE
        String date = book.getUpdateDate() != null ?
                book.getUpdateDate().format(DATE_FORMATTER) :
                LocalDateTime.now().format(DATE_FORMATTER);
        sb.append(date).append(FIELD_DELIMITER);

        // INSNO
        sb.append(0).append(FIELD_DELIMITER);

        // FOLDER
        sb.append(book.getFolder() != null ? escapeField(book.getFolder()) : "").append(FIELD_DELIMITER);

        // LANG
        sb.append(book.getLanguage() != null ? book.getLanguage().toString() : "uk").append(FIELD_DELIMITER);

        // KEYWORDS
        sb.append(book.getKeywords() != null ? escapeField(book.getKeywords()) : "");

        // Додаткові дані (якщо потрібно)
        if (includeExtraData) {
            sb.append(FIELD_DELIMITER).append(book.getRate());
            sb.append(FIELD_DELIMITER).append(book.getProgress());
            sb.append(FIELD_DELIMITER).append(book.getReview() != null ? escapeField(book.getReview()) : "");
        }

        return sb.toString();
    }

    private String buildAuthors(Book book) {
        if (book.getAuthors().isEmpty()) {
            return "Невідомий Автор";
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
        StringBuilder sb = new StringBuilder();
        sb.append(request.getCollectionName() != null ? request.getCollectionName() : "MyHomeLib Collection").append("\n");
        sb.append(request.getOutputFile().getFileName().toString()).append("\n");
        sb.append("0\n"); // Тип колекції (0 – локальна FB2)
        sb.append("Експортовано з MyHomeLib Enterprise\n");
        sb.append("\n"); // URL
        sb.append("\n"); // Скрипт
        return sb.toString();
    }

    private String escapeField(String value) {
        if (value == null) return "";
        return value.replace("\n", " ").replace("\r", " ").trim();
    }
}