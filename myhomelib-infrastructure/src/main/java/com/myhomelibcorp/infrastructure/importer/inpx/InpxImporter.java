package com.myhomelibcorp.infrastructure.importer.inpx;

import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.author.AuthorNameKey;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.service.LanguageResolver;
import com.myhomelibcorp.infrastructure.importengine.InpxReader;
import com.myhomelibcorp.infrastructure.importengine.InpxRecord;
import com.myhomelibcorp.shared.exception.BusinessException;
import com.myhomelibcorp.shared.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

/**
 * Legacy {@link BookImporterPort} adapter for INP/INPX sources.
 *
 * <p>All source decoding, custom {@code structure.info}, legacy charset handling,
 * archive mapping and streaming now come from the single {@link InpxReader}. This
 * prevents the historical split where this adapter interpreted the same INPX file
 * differently from the main catalogue import pipeline.</p>
 */
@Component
@Slf4j
public class InpxImporter implements BookImporterPort {

    private static final int AUTHOR_CACHE_LIMIT = 10_000;

    @Autowired private AuthorRepository authorRepository;
    @Autowired private GenreRepository genreRepository;
    @Autowired private InpxReader inpxReader;

    /** Bounded, per-import object cache. Never mirror the complete authors table in heap. */
    private final Map<AuthorNameKey, Author> authorObjectCache = new LinkedHashMap<>(1024, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<AuthorNameKey, Author> eldest) {
            return size() > AUTHOR_CACHE_LIMIT;
        }
    };
    private final Map<String, GenreId> genreIdCache = new LinkedHashMap<>();

    /**
     * Kept for bootstrap compatibility. Unlike the old one-shot flag this is safe
     * across collection switches: every call refreshes the small genre dictionary
     * and clears collection-scoped author objects.
     */
    public synchronized void initialize() {
        authorObjectCache.clear();
        genreIdCache.clear();
        List<Genre> genres = genreRepository.findAll();
        for (Genre genre : genres) {
            genreIdCache.put(genre.getId().asString(), genre.getId());
            if (genre.getFb2Code() != null && !genre.getFb2Code().isBlank()) {
                genreIdCache.putIfAbsent(genre.getFb2Code(), genre.getId());
            }
        }
        log.info("Завантажено {} жанрових ключів у кеш InpxImporter", genreIdCache.size());
    }

    @Override
    public boolean supports(Path file) {
        if (file == null || file.getFileName() == null) return false;
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".inpx") || name.endsWith(".inp");
    }

    @Override
    public Stream<Book> importBooks(Path file) {
        log.info("Початок streaming-імпорту INP/INPX: {}", file);
        try {
            initialize();
            Iterator<InpxRecord> records = inpxReader.read(file);
            Iterator<Book> books = new MappingIterator(records);
            return StreamSupport.stream(
                            Spliterators.spliteratorUnknownSize(books, Spliterator.ORDERED | Spliterator.NONNULL),
                            false)
                    .onClose(() -> closeRecords(records));
        } catch (BusinessException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.IMPORT_FAILED,
                    "Помилка імпорту INP/INPX: " + rootMessage(e), e);
        }
    }

    @Override public String getFormatName() { return "INPX"; }

    @Override
    public long countBooks(Path file) {
        try {
            return inpxReader.count(file);
        } catch (RuntimeException e) {
            throw new BusinessException(ErrorCode.IMPORT_FAILED,
                    "Не вдалося прочитати INP/INPX для підрахунку: " + rootMessage(e), e);
        }
    }

    private final class MappingIterator implements Iterator<Book> {
        private final Iterator<InpxRecord> delegate;

        private MappingIterator(Iterator<InpxRecord> delegate) {
            this.delegate = delegate;
        }

        @Override public boolean hasNext() { return delegate.hasNext(); }

        @Override
        public Book next() {
            if (!delegate.hasNext()) throw new NoSuchElementException();
            return mapRecord(delegate.next());
        }
    }

    private Book mapRecord(InpxRecord record) {
        List<Author> authors = resolveAuthors(record.field("AUTHOR"));
        List<Genre> genres = resolveGenres(record.field("GENRE"));

        String title = defaultIfBlank(record.field("TITLE"), "Без назви");
        String series = record.field("SERIES");
        int sequenceNumber = parseSequence(record.field("SERNO"));
        long fileSize = parseNonNegativeLong(record.field("SIZE"));

        String logicalBookName = buildBookFileName(record.field("FILE"), record.field("EXT"));
        String archiveName = record.archiveName();
        BookFile bookFile = archiveName == null || archiveName.isBlank()
                ? new BookFile(logicalBookName, "", "", fileSize, null)
                : new BookFile(logicalBookName, archiveName, logicalBookName, fileSize, null);

        BookMetadata metadata = BookMetadata.builder()
                .annotation(record.field("ANNOTATION"))
                .keywords(record.field("KEYWORDS"))
                .language(LanguageResolver.resolve(record.field("LANG")))
                .rate(0)
                .progress(0)
                .build();

        return Book.builder()
                .id(BookId.generate())
                .title(title)
                .authors(authors)
                .genres(genres)
                .series(series)
                .sequenceNumber(sequenceNumber)
                .metadata(metadata)
                .file(bookFile)
                .updateDate(LocalDateTime.now())
                .build();
    }

    private List<Author> resolveAuthors(String raw) {
        List<Author> authors = new ArrayList<>(2);
        if (raw != null && !raw.isBlank() && !raw.equals(":")) {
            for (String item : raw.split(":")) {
                if (item == null || item.isBlank()) continue;
                String[] parts = item.trim().split(",", -1);
                String lastName = part(parts, 0);
                String firstName = part(parts, 1);
                String middleName = part(parts, 2);
                if (firstName.isBlank() && middleName.isBlank() && lastName.isBlank()) continue;
                authors.add(resolveAuthor(firstName, middleName, lastName));
            }
        }
        if (authors.isEmpty()) authors.add(resolveAuthor("", "", "Невідомий Автор"));
        return List.copyOf(authors);
    }

    private Author resolveAuthor(String firstName, String middleName, String lastName) {
        AuthorNameKey key = new AuthorNameKey(firstName, middleName, lastName);
        Author cached = authorObjectCache.get(key);
        if (cached != null) return cached;

        Author resolved = authorRepository.findByName(firstName, middleName, lastName)
                .orElseGet(() -> authorRepository.save(new Author(firstName, middleName, lastName)));
        authorObjectCache.put(key, resolved);
        return resolved;
    }

    private List<Genre> resolveGenres(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        List<Genre> genres = new ArrayList<>(2);
        for (String item : raw.split(":")) {
            String code = item == null ? "" : item.trim();
            if (code.isBlank()) continue;
            GenreId id = genreIdCache.get(code);
            if (id == null) {
                Genre created = genreRepository.save(new Genre(code, code));
                id = created.getId();
                genreIdCache.put(code, id);
            }
            genres.add(new Genre(id, code, null, code));
        }
        return List.copyOf(genres);
    }

    private static int parseSequence(String value) {
        if (value == null || value.isBlank()) return 0;
        try {
            double parsed = Double.parseDouble(value.trim().replace(',', '.'));
            if (!Double.isFinite(parsed) || parsed < Integer.MIN_VALUE || parsed > Integer.MAX_VALUE) return 0;
            return (int) Math.round(parsed);
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private static long parseNonNegativeLong(String value) {
        if (value == null || value.isBlank()) return 0L;
        try {
            long parsed = Long.parseLong(value.trim());
            return Math.max(0L, parsed);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private static String buildBookFileName(String rawName, String rawExtension) {
        String name = defaultIfBlank(rawName, "unknown");
        String extension = rawExtension == null ? "" : rawExtension.trim();
        while (extension.startsWith(".")) extension = extension.substring(1);
        if (extension.isBlank()) return name;
        String suffix = "." + extension;
        return name.toLowerCase(Locale.ROOT).endsWith(suffix.toLowerCase(Locale.ROOT)) ? name : name + suffix;
    }

    private static String part(String[] values, int index) {
        return index < values.length && values[index] != null ? values[index].trim() : "";
    }

    private static String defaultIfBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static void closeRecords(Iterator<InpxRecord> records) {
        if (records instanceof AutoCloseable closeable) {
            try {
                closeable.close();
            } catch (Exception e) {
                throw new IllegalStateException("Не вдалося закрити INP/INPX reader", e);
            }
        }
    }

    private static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
