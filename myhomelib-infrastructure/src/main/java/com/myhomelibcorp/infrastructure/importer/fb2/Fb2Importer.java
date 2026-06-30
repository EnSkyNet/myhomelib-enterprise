package com.myhomelibcorp.infrastructure.importer.fb2;

import com.myhomelibcorp.application.port.out.BookImporterPort;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.infrastructure.parser.fb2.*;
import com.myhomelibcorp.shared.exception.BusinessException;
import com.myhomelibcorp.shared.exception.ErrorCode;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.stream.Stream;

@Component
@Slf4j
public class Fb2Importer implements BookImporterPort {

    private final Fb2AuthorParser authorParser = new Fb2AuthorParser();
    private final Fb2GenreParser genreParser = new Fb2GenreParser();
    private final Fb2TitleParser titleParser = new Fb2TitleParser();
    private final Fb2AnnotationParser annotationParser = new Fb2AnnotationParser();

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".fb2") || name.endsWith(".fbd");
    }

    @Override
    public Stream<Book> importBooks(Path file) {
        log.info("📚 Імпорт FB2 з: {}", file);
        try {
            Book book = parseFb2(file);
            log.info("✅ Успішно імпортовано книгу: '{}', авторів: {}, анотація: {}",
                    book.getTitle(),
                    book.getAuthors().size(),
                    book.getAnnotation() != null && !book.getAnnotation().isEmpty() ? "є" : "немає");
            return Stream.of(book);
        } catch (Exception e) {
            log.error("❌ Помилка імпорту FB2: {}", file, e);
            throw new BusinessException(ErrorCode.IMPORT_FAILED, "Помилка імпорту FB2: " + e.getMessage(), e);
        }
    }

    @Override
    public String getFormatName() {
        return "FB2";
    }

    @Override
    public long countBooks(Path file) {
        return 1;
    }

    private Book parseFb2(Path file) throws Exception {
        log.debug("Парсинг файлу: {}", file);

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

        // Парсимо Title
        String title;
        try (InputStream is = Files.newInputStream(file)) {
            XMLStreamReader xmlReader = factory.createXMLStreamReader(is);
            title = titleParser.parse(xmlReader);
            log.debug("Назва книги: '{}'", title);
        }

        // Парсимо Авторів
        List<Author> authors;
        try (InputStream is = Files.newInputStream(file)) {
            XMLStreamReader xmlReader = factory.createXMLStreamReader(is);
            authors = authorParser.parse(xmlReader);
            log.debug("Знайдено {} авторів", authors.size());
        }

        // Парсимо Жанри
        List<Genre> genres;
        try (InputStream is = Files.newInputStream(file)) {
            XMLStreamReader xmlReader = factory.createXMLStreamReader(is);
            genres = genreParser.parse(xmlReader);
            log.debug("Знайдено {} жанрів", genres.size());
        }

        // Парсимо Анотацію
        String annotation;
        try (InputStream is = Files.newInputStream(file)) {
            XMLStreamReader xmlReader = factory.createXMLStreamReader(is);
            annotation = annotationParser.parse(xmlReader);
            log.debug("Анотація: '{}'", annotation);
        }

        if (authors.isEmpty()) {
            log.warn("Автори не знайдені, додаємо 'Невідомий Автор'");
            authors.add(new Author("", "", "Невідомий Автор"));
        }

        long fileSize = Files.size(file);

        return Book.builder()
                .id(BookId.generate())
                .title(title)
                .authors(authors)
                .genres(genres)
                .series("")
                .sequenceNumber(0)
                .language(LanguageCode.of("ru"))
                .fileName(file.getFileName().toString())
                .folder(file.getParent() != null ? file.getParent().toString() : "")
                .fileSize(fileSize)
                .keywords("")
                .annotation(annotation)
                .updateDate(LocalDateTime.now())
                .build();
    }
}