package com.myhomelibcorp.infrastructure.importer.inpx;

import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.shared.exception.BusinessException;
import com.myhomelibcorp.shared.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
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

    @Autowired
    private AuthorRepository authorRepository;

    @Autowired
    private GenreRepository genreRepository;

    private final Map<String, Author> authorCache = new HashMap<>();
    private final Map<String, Genre> genreCache = new HashMap<>();

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".inpx") || name.endsWith(".inp");
    }

    @Override
    public Stream<Book> importBooks(Path file) {
        log.info("Початок імпорту INPX: {}", file);
        try {
            authorCache.clear();
            genreCache.clear();
            Iterator<Book> iterator = new InpxIterator(file);
            Spliterator<Book> spliterator = Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED);
            return StreamSupport.stream(spliterator, false);
        } catch (Exception e) {
            log.error("Помилка імпорту INPX", e);
            throw new BusinessException(ErrorCode.IMPORT_FAILED, "Помилка імпорту INPX: " + e.getMessage(), e);
        }
    }

    @Override
    public String getFormatName() {
        return "INPX";
    }

    @Override
    public long countBooks(Path file) {
        try (ZipInputStream zis = new ZipInputStream(Files.newInputStream(file))) {
            ZipEntry entry;
            while ((entry = zis.getNextEntry()) != null) {
                if (entry.getName().endsWith(".inp")) {
                    long count = 0;
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8))) {
                        while (reader.readLine() != null) count++;
                    }
                    return count;
                }
                zis.closeEntry();
            }
        } catch (Exception e) {
            log.warn("Не вдалося підрахувати кількість книг у INPX", e);
        }
        return -1;
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
                    log.info("Знайдено INP файл: {}", entry.getName());
                    tmpReader = new BufferedReader(new InputStreamReader(zis, StandardCharsets.UTF_8));
                    break;
                }
                zis.closeEntry();
            }
            if (tmpReader == null) {
                log.warn("INP файл не знайдено в архіві");
                this.reader = null;
                this.finished = true;
                return;
            }
            this.reader = tmpReader;
            this.nextLine = reader.readLine();
            if (this.nextLine == null) {
                log.warn("INP файл порожній");
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
                    log.info("Прочитано {} рядків, розпарсено {} книг", lineCount, bookCount);
                }
            } catch (Exception e) {
                finished = true;
                log.error("Помилка читання INPX", e);
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
                    log.debug("Замало полів: {} (очікується >= 8)", parts.length);
                    return null;
                }

                // ---- Автори ----
                List<Author> authors = new ArrayList<>();
                String authorsStr = parts[0].trim();
                if (!authorsStr.isEmpty() && !authorsStr.equals(":")) {
                    // Розділяємо авторів за двокрапкою
                    String[] authorEntries = authorsStr.split(":");
                    for (String authorEntry : authorEntries) {
                        authorEntry = authorEntry.trim();
                        if (authorEntry.isEmpty()) continue;
                        // Розділяємо частини автора за комами: прізвище,ім'я,по-батькові
                        String[] nameParts = authorEntry.split(",");
                        String lastName = nameParts.length > 0 ? nameParts[0].trim() : "";
                        String firstName = nameParts.length > 1 ? nameParts[1].trim() : "";
                        String middleName = nameParts.length > 2 ? nameParts[2].trim() : "";
                        // Якщо всі поля порожні – пропускаємо
                        if (lastName.isEmpty() && firstName.isEmpty() && middleName.isEmpty()) continue;
                        // Шукаємо автора в кеші
                        String key = firstName + "|" + middleName + "|" + lastName;
                        Author author = authorCache.get(key);
                        if (author == null) {
                            author = new Author(firstName, middleName, lastName);
                            authorCache.put(key, author);
                        }
                        authors.add(author);
                    }
                }
                if (authors.isEmpty()) {
                    String defaultAuthor = "Невідомий Автор";
                    Author author = authorCache.get(defaultAuthor);
                    if (author == null) {
                        author = new Author("", "", defaultAuthor);
                        authorCache.put(defaultAuthor, author);
                    }
                    authors.add(author);
                }

                // ---- Жанри ----
                List<Genre> genres = new ArrayList<>();
                String genresStr = parts[1].trim();
                if (!genresStr.isEmpty()) {
                    for (String code : genresStr.split(":")) {
                        String clean = code.trim();
                        if (!clean.isEmpty()) {
                            Genre genre = genreCache.get(clean);
                            if (genre == null) {
                                genre = new Genre(clean, clean);
                                genreCache.put(clean, genre);
                            }
                            genres.add(genre);
                        }
                    }
                }

                // ---- Назва ----
                String title = parts[2].trim();
                if (title.isEmpty()) title = "Без назви";

                // ---- Серія ----
                String series = parts[3].trim();

                // ---- Номер у серії ----
                int seqNumber = 0;
                if (parts.length > 4 && !parts[4].trim().isEmpty()) {
                    try {
                        seqNumber = Integer.parseInt(parts[4].trim());
                    } catch (NumberFormatException ignored) {}
                }

                // ---- Ім'я файлу ----
                String fileName = parts.length > 5 ? parts[5].trim() : "unknown.fb2";

                // ---- Розмір файлу ----
                long fileSize = parts.length > 6 && !parts[6].trim().isEmpty() ?
                        Long.parseLong(parts[6].trim()) : 0;

                // ---- Мова ----
                String languageCode = parts.length > 8 && !parts[8].trim().isEmpty() ?
                        parts[8].trim() : "uk";
                try {
                    LanguageCode.of(languageCode);
                } catch (IllegalArgumentException e) {
                    languageCode = "uk";
                }

                // ---- Ключові слова ----
                String keywords = parts.length > 12 ? parts[12].trim() : "";

                // ---- Анотація ----
                String annotation = parts.length > 13 ? parts[13].trim() : "";

                // ---- Створення метаданих та файлу ----
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
                log.warn("Помилка парсингу рядка: {}", line, e);
                return null;
            }
        }
    }
}