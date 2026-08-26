package com.myhomelibcorp.infrastructure.importer.inpx;

import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.*;
import com.myhomelibcorp.shared.exception.BusinessException;
import com.myhomelibcorp.shared.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
@Slf4j
public class InpxImporter implements BookImporterPort {

    private static final char FIELD_DELIMITER = (char) 4;
    private static final String FALLBACK_DELIMITER = "|";

    @Autowired
    private AuthorRepository authorRepository;

    @Autowired
    private GenreRepository genreRepository;

    // Bounded per-import cache: do not mirror the complete authors table in heap.
    private static final int AUTHOR_CACHE_LIMIT = 10_000;
    private final Map<String, Author> authorObjectCache = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Author> eldest) {
            return size() > AUTHOR_CACHE_LIMIT;
        }
    };
    private final Map<String, GenreId> genreIdCache = new HashMap<>();

    private boolean initialized = false;

    /**
     * Явна ініціалізація – викликається після вибору колекції.
     */
    public void initialize() {
        if (initialized) return;
        // Authors are resolved lazily. Loading every author here makes startup/import
        // proportional to catalogue size and causes large heap spikes.
        loadGenreCache();
        initialized = true;
    }

    private void loadGenreCache() {
        try {
            List<Genre> genres = genreRepository.findAll();
            for (Genre genre : genres) {
                genreIdCache.put(genre.getId().asString(), genre.getId());
            }
            log.info("Завантажено {} жанрів у кеш InpxImporter", genreIdCache.size());
        } catch (Exception e) {
            log.warn("Не вдалося завантажити жанри у кеш InpxImporter", e);
        }
    }

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".inpx") || name.endsWith(".inp");
    }

    @Override
    public Stream<Book> importBooks(Path file) {
        log.info("Початок імпорту INPX: {}", file);
        try {
            // Використовуємо ZipFile замість ZipInputStream для швидшого доступу
            ZipFile zipFile = new ZipFile(file.toFile());
            ZipEntry inpEntry = null;
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".inp")) {
                    inpEntry = entry;
                    break;
                }
            }
            if (inpEntry == null) {
                throw new BusinessException(ErrorCode.IMPORT_FAILED, "INP файл не знайдено в архіві");
            }

            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(zipFile.getInputStream(inpEntry), StandardCharsets.UTF_8)
            );

            // Якщо кеші ще не завантажені – завантажуємо
            if (!initialized) {
                initialize();
            }

            Iterator<Book> iterator = new InpxIterator(reader, zipFile);
            Spliterator<Book> spliterator = Spliterators.spliteratorUnknownSize(iterator, Spliterator.ORDERED);
            return StreamSupport.stream(spliterator, false).onClose(() -> {
                try {
                    reader.close();
                    zipFile.close();
                } catch (Exception e) {
                    log.warn("Помилка закриття ZIP", e);
                }
            });
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
        try (ZipFile zipFile = new ZipFile(file.toFile())) {
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry.getName().endsWith(".inp")) {
                    long count = 0;
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(zipFile.getInputStream(entry), StandardCharsets.UTF_8))) {
                        while (reader.readLine() != null) count++;
                    }
                    return count;
                }
            }
        } catch (Exception e) {
            log.warn("Не вдалося підрахувати кількість книг у INPX", e);
        }
        return -1;
    }

    // ==================== ВНУТРІШНІЙ КЛАС ІТЕРАТОРА ====================

    private class InpxIterator implements Iterator<Book> {
        private final BufferedReader reader;
        private final ZipFile zipFile;
        private String nextLine;
        private boolean finished;
        private int lineCount = 0;
        private int bookCount = 0;

        public InpxIterator(BufferedReader reader, ZipFile zipFile) {
            this.reader = reader;
            this.zipFile = zipFile;
            try {
                this.nextLine = reader.readLine();
                if (this.nextLine == null) {
                    this.finished = true;
                    reader.close();
                }
            } catch (Exception e) {
                this.finished = true;
                log.error("Помилка ініціалізації читання INPX", e);
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
                    zipFile.close();
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
                // Власний парсер полів (без regex)
                String[] parts = splitFields(line, FIELD_DELIMITER);
                if (parts.length < 8) {
                    // спроба з резервним роздільником
                    parts = splitFields(line, FALLBACK_DELIMITER.charAt(0));
                    if (parts.length < 8) {
                        log.debug("Замало полів: {} (очікується >= 8)", parts.length);
                        return null;
                    }
                }

                // ---- Автори ----
                List<Author> authors = new ArrayList<>(2);
                String authorsStr = parts[0].trim();
                if (!authorsStr.isEmpty() && !authorsStr.equals(":")) {
                    String[] authorEntries = authorsStr.split(":");
                    for (String authorEntry : authorEntries) {
                        authorEntry = authorEntry.trim();
                        if (authorEntry.isEmpty()) continue;
                        String[] nameParts = authorEntry.split(",");
                        String lastName = nameParts.length > 0 ? nameParts[0].trim() : "";
                        String firstName = nameParts.length > 1 ? nameParts[1].trim() : "";
                        String middleName = nameParts.length > 2 ? nameParts[2].trim() : "";
                        if (lastName.isEmpty() && firstName.isEmpty() && middleName.isEmpty()) continue;

                        String key = firstName + "|" + middleName + "|" + lastName;
                        Author author = resolveAuthor(key, firstName, middleName, lastName);
                        authors.add(author);
                    }
                }
                if (authors.isEmpty()) {
                    String key = "||Неведомий Автор";
                    authors.add(resolveAuthor(key, "", "", "Неведомий Автор"));
                }

                // ---- Жанри ----
                List<Genre> genres = new ArrayList<>(2);
                String genresStr = parts[1].trim();
                if (!genresStr.isEmpty()) {
                    for (String code : genresStr.split(":")) {
                        String clean = code.trim();
                        if (!clean.isEmpty()) {
                            GenreId genreId = genreIdCache.get(clean);
                            if (genreId == null) {
                                // Жанру немає – створюємо
                                Genre newGenre = new Genre(clean, clean);
                                genreRepository.save(newGenre);
                                genreId = newGenre.getId();
                                genreIdCache.put(clean, genreId);
                            }
                            Genre genre = new Genre(genreId, clean, null, clean);
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
                BookMetadata metadata = BookMetadata.builder()
                        .annotation(annotation)
                        .keywords(keywords)
                        .language(LanguageCode.of(languageCode))
                        .rate(0)
                        .progress(0)
                        .build();

                BookFile bookFile = new BookFile(
                        fileName,
                        "",  // folder – буде заповнено при збереженні, якщо потрібно
                        "",  // archive_entry – для zip-архівів
                        fileSize,
                        null // collectionRoot
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

        private Author resolveAuthor(String key, String firstName, String middleName, String lastName) {
            Author cached = authorObjectCache.get(key);
            if (cached != null) {
                return cached;
            }

            // Database schema historically identifies authors by first+last name.
            // Reuse that persistent row on a cache miss instead of blindly inserting.
            Author resolved = authorRepository.findByFullName(firstName, lastName)
                    .orElseGet(() -> authorRepository.save(new Author(firstName, middleName, lastName)));
            authorObjectCache.put(key, resolved);
            return resolved;
        }

        /**
         * Швидкий парсер полів без використання регулярних виразів.
         */
        private String[] splitFields(String line, char delimiter) {
            List<String> fields = new ArrayList<>();
            int start = 0;
            for (int i = 0; i < line.length(); i++) {
                if (line.charAt(i) == delimiter) {
                    fields.add(line.substring(start, i));
                    start = i + 1;
                }
            }
            fields.add(line.substring(start));
            return fields.toArray(new String[0]);
        }
    }
}