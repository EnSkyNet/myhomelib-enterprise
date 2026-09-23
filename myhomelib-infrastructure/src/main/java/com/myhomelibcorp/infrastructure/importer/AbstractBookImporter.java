package com.myhomelibcorp.infrastructure.importer;

import java.io.IOException;
import com.myhomelibcorp.application.port.out.importer.BookImporterPort;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.stream.Stream;

/**
 * Абстрактний базовий клас для всіх імпортерів книг.
 * Спрощує додавання нових форматів (EPUB, PDF, MOBI тощо).
 */
@Slf4j
public abstract class AbstractBookImporter implements BookImporterPort {

    /**
     * Парсить книгу з файлу та повертає об'єкт Book.
     * Кожен конкретний імпортер реалізує цей метод.
     */
    protected abstract Book parseBook(Path file) throws Exception;

    /**
     * Створює Book з окремих компонентів.
     * Можна перевизначити для специфічної логіки.
     */
    protected Book createBook(
            String title,
            List<Author> authors,
            List<Genre> genres,
            String series,
            Integer sequenceNumber,
            BookMetadata metadata,
            BookFile file,
            LocalDateTime updateDate
    ) {
        return Book.builder()
                .id(BookId.generate())
                .title(title)
                .authors(authors)
                .genres(genres)
                .series(series != null ? series : "")
                .sequenceNumber(sequenceNumber != null ? sequenceNumber : 0)
                .metadata(metadata != null ? metadata : BookMetadata.empty())
                .file(file != null ? file : BookFile.empty())
                .updateDate(updateDate)
                .local(true)
                .build();
    }

    protected LocalDateTime fileUpdateTime(Path file) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(file, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) {
            throw new IOException("Import source is not a regular file: " + file);
        }
        return LocalDateTime.ofInstant(attributes.lastModifiedTime().toInstant(), ZoneId.systemDefault());
    }

    @Override
    public Stream<Book> importBooks(Path file) {
        log.info("📚 Імпорт {} з: {}", getFormatName(), file);
        try {
            Path safeFile = SafeFilePublication.requireSafeRegularFile(file, getFormatName() + " import source");
            Book book = parseBook(safeFile);
            log.info("✅ Успішно імпортовано книгу: '{}', авторів: {}, серія: '{}'",
                    book.getTitle(),
                    book.getAuthors().size(),
                    book.getSeries() != null && !book.getSeries().isBlank() ? book.getSeries() : "немає");
            return Stream.of(book);
        } catch (Exception e) {
            log.error("❌ Помилка імпорту {}: {}", getFormatName(), file, e);
            throw new RuntimeException("Помилка імпорту " + getFormatName() + ": " + e.getMessage(), e);
        }
    }

    @Override
    public long countBooks(Path file) {
        if (file == null) return 0;
        try {
            SafeFilePublication.requireSafeRegularFile(file, getFormatName() + " import source");
            return 1;
        } catch (IOException unsafe) {
            return 0;
        }
    }

    /**
     * Допоміжний метод для створення автора.
     */
    protected Author createAuthor(String firstName, String middleName, String lastName) {
        return new Author(firstName, middleName, lastName);
    }

    /**
     * Допоміжний метод для створення жанру.
     */
    protected Genre createGenre(String code, String name) {
        return new Genre(code, name);
    }

    /**
     * Допоміжний метод для створення LanguageCode.
     */
    protected LanguageCode createLanguage(String code) {
        return LanguageCode.of(code);
    }
}