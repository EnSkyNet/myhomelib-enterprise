package com.myhomelibcorp.infrastructure.importer.inpx;

import com.myhomelibcorp.application.port.out.AuthorRepository;
import com.myhomelibcorp.application.port.out.BookImporterPort;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.shared.exception.BusinessException;
import com.myhomelibcorp.shared.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

@Component
@RequiredArgsConstructor
@Slf4j
public class InpxImporter implements BookImporterPort {

    private static final char FIELD_DELIMITER = (char) 4;
    private static final String FALLBACK_DELIMITER = "|";

    private final AuthorRepository authorRepository;

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".inpx") || name.endsWith(".inp");
    }

    @Override
    public List<Book> importBooks(Path file) {
        log.info("Імпорт INPX з: {}", file);
        List<Book> books = new ArrayList<>();

        try (var fis = Files.newInputStream(file);
             var zis = new ZipInputStream(fis)) {

            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith(".inp")) {
                    log.info("Обробка INP файлу: {}", entry.getName());
                    try (var reader = new BufferedReader(
                            new InputStreamReader(zis, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.isBlank()) continue;
                            Book book = parseInpxLine(line);
                            if (book != null) {
                                books.add(book);
                            }
                        }
                    }
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            log.error("Помилка імпорту INPX", e);
            throw new BusinessException(ErrorCode.IMPORT_FAILED, "Помилка імпорту INPX: " + e.getMessage(), e);
        }

        log.info("Імпортовано {} книг з INPX", books.size());
        return books;
    }

    @Override
    public String getFormatName() {
        return "INPX";
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
                log.warn("Неповний запис INPX: {} полів (очікується >= 8)", parts.length);
                return null;
            }

            List<Author> authors = new ArrayList<>();
            String authorsStr = parts[0].trim();
            if (!authorsStr.isEmpty() && !authorsStr.equals(":")) {
                for (String name : authorsStr.split(",")) {
                    String clean = name.trim();
                    if (!clean.isEmpty() && !clean.equals(":")) {
                        Author author = createOrGetAuthor(clean);
                        authors.add(author);
                    }
                }
            }
            if (authors.isEmpty()) {
                authors.add(createOrGetAuthor("Невідомий Автор"));
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

            String language = parts.length > 8 && !parts[8].trim().isEmpty() ?
                    parts[8].trim() : "ru";

            String keywords = parts.length > 12 ? parts[12].trim() : "";
            String annotation = parts.length > 13 ? parts[13].trim() : "";

            return Book.builder()
                    .id(BookId.generate())
                    .title(title)
                    .authors(authors)
                    .genres(genres)
                    .series(series)
                    .sequenceNumber(seqNumber)
                    .language(LanguageCode.of(language))
                    .fileName(fileName)
                    .folder("")
                    .fileSize(fileSize)
                    .keywords(keywords)
                    .annotation(annotation)
                    .updateDate(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            log.warn("Помилка парсингу рядка INPX: {}", line, e);
            return null;
        }
    }

    private Author createOrGetAuthor(String fullName) {
        String[] parts = fullName.split(" ", 3);
        String lastName = parts[0];
        String firstName = parts.length > 1 ? parts[1] : "";
        String middleName = parts.length > 2 ? parts[2] : "";

        Optional<Author> existing = authorRepository.findByFullName(firstName, lastName);
        if (existing.isPresent()) {
            return existing.get();
        }

        Author newAuthor = new Author(firstName, middleName, lastName);
        return authorRepository.save(newAuthor);
    }
}