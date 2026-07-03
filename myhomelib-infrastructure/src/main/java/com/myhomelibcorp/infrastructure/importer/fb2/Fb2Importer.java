package com.myhomelibcorp.infrastructure.importer.fb2;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.infrastructure.importer.AbstractBookImporter;
import com.myhomelibcorp.infrastructure.parser.fb2.*;
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

@Component
@Slf4j
public class Fb2Importer extends AbstractBookImporter {

    private final Fb2AuthorParser authorParser = new Fb2AuthorParser();
    private final Fb2GenreParser genreParser = new Fb2GenreParser();
    private final Fb2TitleParser titleParser = new Fb2TitleParser();
    private final Fb2AnnotationParser annotationParser = new Fb2AnnotationParser();
    private final Fb2SequenceParser sequenceParser = new Fb2SequenceParser();

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".fb2") || name.endsWith(".fbd");
    }

    @Override
    public String getFormatName() {
        return "FB2";
    }

    @Override
    protected Book parseBook(Path file) throws Exception {
        log.debug("Парсинг FB2 файлу: {}", file);

        XMLInputFactory factory = XMLInputFactory.newInstance();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);

        // Парсимо Title
        String title;
        try (InputStream is = Files.newInputStream(file)) {
            XMLStreamReader xmlReader = factory.createXMLStreamReader(is);
            title = titleParser.parse(xmlReader);
        }

        // Парсимо Авторів
        List<Author> authors;
        try (InputStream is = Files.newInputStream(file)) {
            XMLStreamReader xmlReader = factory.createXMLStreamReader(is);
            authors = authorParser.parse(xmlReader);
        }

        // Парсимо Жанри
        List<Genre> genres;
        try (InputStream is = Files.newInputStream(file)) {
            XMLStreamReader xmlReader = factory.createXMLStreamReader(is);
            genres = genreParser.parse(xmlReader);
        }

        // Парсимо Анотацію
        String annotation;
        try (InputStream is = Files.newInputStream(file)) {
            XMLStreamReader xmlReader = factory.createXMLStreamReader(is);
            annotation = annotationParser.parse(xmlReader);
        }

        // Парсимо серію
        String series = "";
        int sequenceNumber = 0;
        try (InputStream is = Files.newInputStream(file)) {
            XMLStreamReader xmlReader = factory.createXMLStreamReader(is);
            Fb2ParserContext context = new Fb2ParserContext();
            while (xmlReader.hasNext()) {
                int event = xmlReader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String localName = xmlReader.getLocalName();
                    if ("title-info".equalsIgnoreCase(localName)) {
                        context.setInTitleInfo(true);
                    }
                    if (context.isInTitleInfo() && "sequence".equalsIgnoreCase(localName)) {
                        sequenceParser.parse(xmlReader, context);
                    }
                }
                if (event == XMLStreamConstants.END_ELEMENT) {
                    if ("title-info".equalsIgnoreCase(xmlReader.getLocalName())) {
                        break;
                    }
                }
            }
            series = context.getSeries() != null ? context.getSeries() : "";
            sequenceNumber = context.getSequenceNumber();
        }

        if (authors.isEmpty()) {
            log.warn("Автори не знайдені, додаємо 'Невідомий Автор'");
            authors.add(createAuthor("", "", "Невідомий Автор"));
        }

        long fileSize = Files.size(file);

        BookMetadata metadata = BookMetadata.builder()
                .annotation(annotation)
                .keywords("")
                .language(createLanguage("ru"))
                .rate(0)
                .progress(0)
                .build();

        BookFile bookFile = new BookFile(
                file.getFileName().toString(),
                file.getParent() != null ? file.getParent().toString() : "",
                "",
                fileSize,
                null
        );

        return createBook(
                title,
                authors,
                genres,
                series,
                sequenceNumber,
                metadata,
                bookFile,
                LocalDateTime.now()
        );
    }
}