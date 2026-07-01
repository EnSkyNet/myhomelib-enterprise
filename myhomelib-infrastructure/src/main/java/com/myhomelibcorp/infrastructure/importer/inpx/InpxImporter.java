package com.myhomelibcorp.infrastructure.importer.inpx;

import com.myhomelibcorp.application.port.out.BookImporterPort;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.shared.exception.BusinessException;
import com.myhomelibcorp.shared.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
@Slf4j
public class InpxImporter implements BookImporterPort {

    private static final char FIELD_DELIMITER = (char) 4;
    private static final String FALLBACK_DELIMITER = "|";

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".inpx") || name.endsWith(".inp");
    }

    @Override
    public Stream<Book> importBooks(Path file) {
        log.info("📂 Імпорт INPX з: {}", file);
        try {
            Iterator<Book> iterator = new InpxIterator(file);
            Spliterator<Book> spliterator = Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED);
            return StreamSupport.stream(spliterator, false);
        } catch (Exception e) {
            log.error("❌ Помилка імпорту INPX", e);
            throw new BusinessException(ErrorCode.IMPORT_FAILED, "Помилка імпорту INPX: " + e.getMessage(), e);
        }
    }

    @Override
    public String getFormatName() {
        return "INPX";
    }

    private class InpxIterator implements Iterator<Book> {
        private final ZipInputStream zis;
        private final BufferedReader reader;
        private String nextLine;
        private boolean finished;
        private int lineCount = 0;
        private int bookCount = 0;

        public InpxIterator(Path file) throws Exception {
            this.zis = new ZipInputStream(Files.newInputStream(file));
            ZipEntry entry;
            BufferedReader tmpReader = null;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith(".inp")) {
                    log.info("📄 Знайдено INP файл: {}", entry.getName());
                    tmpReader = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
                    break;
                }
                zis.closeEntry();
            }
            if (tmpReader == null) {
                log.warn("⚠️ INP файл не знайдено в архіві");
                this.reader = null;
                this.finished = true;
                return;
            }
            this.reader = tmpReader;
            this.nextLine = reader.readLine();
            if (this.nextLine == null) {
                log.warn("⚠️ INP файл порожній");
                this.finished = true;
            }
        }

        @Override
        public boolean hasNext() {
            return !finished && nextLine != null;
        }

        @Override
        public Book next() {
            if (finished || nextLine == null) return null;
            String line = nextLine;
            lineCount++;
            try {
                nextLine = reader.readLine();
                if (nextLine == null) {
                    finished = true;
                    reader.close();
                    zis.close();
                    log.info("📊 Прочитано {} рядків, розпарсено {} книг", lineCount, bookCount);
                }
            } catch (Exception e) {
                finished = true;
                log.error("❌ Помилка читання INPX", e);
            }
            Book book = parseInpxLine(line);
            if (book != null) bookCount++;
            return book;
        }

        private Book parseInpxLine(String line) {
            try {
                String[] parts;
                if (line.indexOf(FIELD_DELIMITER) > 0) {
                    parts = line.split(String.valueOf(FIELD_DELIMITER), -1);
                } else {
                    parts = line.split("\\" + FALLBACK_DELIMITER, -1);
                }

                if (parts.length < 8) {
                    log.debug("❌ Замало полів: {} (очікується >= 8)", parts.length);
                    return null;
                }

                List<Author> authors = new ArrayList<>();
                String authorsStr = parts[0].trim();
                if (!authorsStr.isEmpty() && !authorsStr.equals(":")) {
                    for (String name : authorsStr.split(",")) {
                        String clean = name.trim();
                        if (!clean.isEmpty() && !clean.equals(":")) {
                            String[] nameParts = clean.split(" ", 3);
                            String lastName = nameParts[0];
                            String firstName = nameParts.length > 1 ? nameParts[1] : "";
                            String middleName = nameParts.length > 2 ? nameParts[2] : "";
                            authors.add(new Author(firstName, middleName, lastName));
                        }
                    }
                }
                if (authors.isEmpty()) {
                    authors.add(new Author("", "", "Невідомий Автор"));
                }

                List<Genre> genres = new ArrayList<>();
                String genresStr = parts[1].trim();
                if (!genresStr.isEmpty()) {
                    for (String code : genresStr.split(":")) {
                        String clean = code.trim();
                        if (!clean.isEmpty()) {
                            genres.add(new Genre(clean, clean));
                        }
                    }
                }

                String title = parts[2].trim();
                if (title.isEmpty()) title = "Без назви";

                String series = parts[3].trim();
                int seqNumber = 0;
                if (parts.length > 4 && !parts[4].trim().isEmpty()) {
                    try {
                        seqNumber = Integer.parseInt(parts[4].trim());
                    } catch (NumberFormatException ignored) {}
                }

                String fileName = parts.length > 5 ? parts[5].trim() : "unknown.fb2";
                long fileSize = parts.length > 6 && !parts[6].trim().isEmpty() ?
                        Long.parseLong(parts[6].trim()) : 0;

                String languageCode = parts.length > 8 && !parts[8].trim().isEmpty() ?
                        parts[8].trim() : "ru";

                String keywords = parts.length > 12 ? parts[12].trim() : "";
                String annotation = parts.length > 13 ? parts[13].trim() : "";

                // Створюємо metadata та file
                var metadata = com.myhomelibcorp.domain.model.valueobject.BookMetadata.builder()
                        .annotation(annotation)
                        .keywords(keywords)
                        .language(LanguageCode.of(languageCode))
                        .rate(0)
                        .progress(0)
                        .build();

                var bookFile = new com.myhomelibcorp.domain.model.valueobject.BookFile(
                        fileName,
                        "",
                        "",
                        fileSize,
                        null
                );

                return Book.builder()
                        .id(BookId.generate())
                        .title(title)
                        .authors(authors)
                        .genres(genres)
                        .series(series)
                        .sequenceNumber(seqNumber)
                        .metadata(metadata)
                        .file(bookFile)
                        .updateDate(LocalDateTime.now())
                        .build();

            } catch (Exception e) {
                log.warn("❌ Помилка парсингу рядка: {}", line, e);
                return null;
            }
        }
    }
}