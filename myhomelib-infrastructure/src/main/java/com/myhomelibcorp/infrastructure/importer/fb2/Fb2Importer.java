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
import javax.xml.stream.XMLStreamConstants;
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
    private final Fb2SequenceParser sequenceParser = new Fb2SequenceParser();
    private final Fb2LanguageParser languageParser = new Fb2LanguageParser();
    private final Fb2KeywordsParser keywordsParser = new Fb2KeywordsParser();

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".fb2") || name.endsWith(".fbd");
    }

    @Override
    public Stream<Book> importBooks(Path file) {
        log.info("Імпорт FB2 з: {}", file);
        try {
            Book book = parseFb2(file);
            return Stream.of(book);
        } catch (Exception e) {
            log.error("Помилка імпорту FB2", e);
            throw new BusinessException(ErrorCode.IMPORT_FAILED, "Помилка імпорту FB2: " + e.getMessage(), e);
        }
    }

    @Override
    public String getFormatName() {
        return "FB2";
    }

    private Book parseFb2(Path file) throws Exception {
        try (InputStream inputStream = Files.newInputStream(file)) {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader reader = factory.createXMLStreamReader(inputStream);

            Fb2ParserContext context = new Fb2ParserContext();

            while (reader.hasNext()) {
                int event = reader.next();

                switch (event) {
                    case XMLStreamConstants.START_ELEMENT:
                        String localName = reader.getLocalName();
                        context.setCurrentElement(localName);

                        if ("title-info".equals(localName)) {
                            context.setInTitleInfo(true);
                        }

                        // Обробка через парсери
                        titleParser.parse(localName, "", context); // title отримаємо через characters
                        genreParser.parse(localName, "", context);
                        languageParser.parse(localName, "", context);
                        keywordsParser.parse(localName, "", context);
                        annotationParser.parse(localName, "", context);

                        if ("sequence".equals(localName)) {
                            sequenceParser.parse(reader, context);
                        }
                        break;

                    case XMLStreamConstants.CHARACTERS:
                        String text = reader.getText();
                        if (text == null || text.isBlank()) break;

                        // Передаємо текст у відповідні парсери
                        titleParser.parse(context.getCurrentElement(), text, context);
                        genreParser.parse(context.getCurrentElement(), text, context);
                        languageParser.parse(context.getCurrentElement(), text, context);
                        keywordsParser.parse(context.getCurrentElement(), text, context);
                        annotationParser.parse(context.getCurrentElement(), text, context);
                        authorParser.parse(context.getCurrentElement(), text, context);
                        break;

                    case XMLStreamConstants.END_ELEMENT:
                        String endName = reader.getLocalName();

                        if ("title-info".equals(endName)) {
                            context.setInTitleInfo(false);
                        }

                        if ("author".equals(endName)) {
                            authorParser.finalizeAuthor(context);
                        }

                        if ("annotation".equals(endName)) {
                            annotationParser.finishAnnotation(context);
                        }

                        context.setCurrentElement("");
                        break;
                }
            }
            reader.close();

            // Якщо авторів немає – додаємо "Невідомий Автор"
            List<Author> authors = context.getAuthors();
            if (authors.isEmpty()) {
                authors.add(new Author("", "", "Невідомий Автор"));
            }

            // Жанри
            List<Genre> genres = context.getGenres();

            // Аннотація
            String annotationText = context.getAnnotation().toString().replaceAll("\\s+", " ").trim();

            long fileSize = Files.size(file);

            return Book.builder()
                    .id(BookId.generate())
                    .title(context.getTitle())
                    .authors(authors)
                    .genres(genres)
                    .series(context.getSeries())
                    .sequenceNumber(context.getSequenceNumber())
                    .language(LanguageCode.of(context.getLanguage()))
                    .fileName(file.getFileName().toString())
                    .folder(file.getParent() != null ? file.getParent().toString() : "")
                    .fileSize(fileSize)
                    .keywords(context.getKeywords())
                    .annotation(annotationText)
                    .updateDate(LocalDateTime.now())
                    .build();
        }
    }
}