package com.myhomelibcorp.infrastructure.importer.fb2;

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

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class Fb2Importer implements BookImporterPort {

    @Override
    public boolean supports(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".fb2") || name.endsWith(".fbd");
    }

    @Override
    public List<Book> importBooks(Path file) {
        log.info("Імпорт FB2 з: {}", file);
        List<Book> books = new ArrayList<>();

        try (InputStream inputStream = Files.newInputStream(file)) {
            Book book = parseFb2(inputStream, file);
            books.add(book);
        } catch (Exception e) {
            log.error("Помилка імпорту FB2", e);
            throw new BusinessException(ErrorCode.IMPORT_FAILED, "Помилка імпорту FB2: " + e.getMessage(), e);
        }

        return books;
    }

    @Override
    public String getFormatName() {
        return "FB2";
    }

    private Book parseFb2(InputStream inputStream, Path file) {
        try {
            XMLInputFactory factory = XMLInputFactory.newInstance();
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            XMLStreamReader reader = factory.createXMLStreamReader(inputStream);

            String title = "Без назви";
            List<Author> authors = new ArrayList<>();
            List<Genre> genres = new ArrayList<>();
            String series = "";
            int seqNumber = 0;
            String language = "ru";
            String keywords = "";
            StringBuilder annotation = new StringBuilder();

            String currentElement = "";
            boolean inTitleInfo = false;
            boolean inAnnotation = false;

            String firstName = "", middleName = "", lastName = "";

            while (reader.hasNext()) {
                int event = reader.next();
                switch (event) {
                    case XMLStreamConstants.START_ELEMENT:
                        currentElement = reader.getLocalName();
                        if ("title-info".equals(currentElement)) {
                            inTitleInfo = true;
                        } else if ("annotation".equals(currentElement) && inTitleInfo) {
                            inAnnotation = true;
                        } else if ("author".equals(currentElement) && inTitleInfo) {
                            firstName = middleName = lastName = "";
                        } else if ("sequence".equals(currentElement) && inTitleInfo) {
                            String nameAttr = reader.getAttributeValue(null, "name");
                            if (nameAttr != null && !nameAttr.isBlank()) {
                                series = nameAttr.trim();
                            }
                            String numAttr = reader.getAttributeValue(null, "number");
                            if (numAttr != null && !numAttr.isBlank()) {
                                try {
                                    seqNumber = Integer.parseInt(numAttr.trim());
                                } catch (NumberFormatException ignored) {}
                            }
                        }
                        break;

                    case XMLStreamConstants.CHARACTERS:
                        String text = reader.getText();
                        if (text == null || text.isBlank()) break;

                        if (inTitleInfo && !inAnnotation) {
                            String trimmed = text.trim();
                            if (trimmed.isEmpty()) break;

                            switch (currentElement) {
                                case "book-title": title = trimmed; break;
                                case "first-name": firstName = trimmed; break;
                                case "middle-name": middleName = trimmed; break;
                                case "last-name": lastName = trimmed; break;
                                case "genre":
                                    genres.add(new Genre(trimmed, trimmed));
                                    break;
                                case "lang": language = trimmed.toLowerCase(); break;
                                case "keywords": keywords = trimmed; break;
                            }
                        } else if (inAnnotation) {
                            annotation.append(text);
                        }
                        break;

                    case XMLStreamConstants.END_ELEMENT:
                        String end = reader.getLocalName();
                        if ("title-info".equals(end)) {
                            inTitleInfo = false;
                        } else if ("annotation".equals(end)) {
                            inAnnotation = false;
                        } else if ("author".equals(end) && inTitleInfo) {
                            if (!lastName.isEmpty() || !firstName.isEmpty()) {
                                Author author = new Author(
                                        com.myhomelibcorp.domain.model.valueobject.AuthorId.generate(),
                                        firstName, middleName, lastName
                                );
                                authors.add(author);
                            }
                        }
                        if (!inAnnotation) {
                            currentElement = "";
                        }
                        break;
                }
            }

            reader.close();

            if (authors.isEmpty()) {
                authors.add(new Author(
                        com.myhomelibcorp.domain.model.valueobject.AuthorId.generate(),
                        "", "", "Невідомий Автор"
                ));
            }

            String annotationText = annotation.toString().replaceAll("\\s+", " ").trim();

            long fileSize = Files.size(file);

            return Book.builder()
                    .id(BookId.generate())
                    .title(title)
                    .authors(authors)
                    .genres(genres)
                    .series(series)
                    .sequenceNumber(seqNumber)
                    .language(LanguageCode.of(language))
                    .fileName(file.getFileName().toString())
                    .folder(file.getParent() != null ? file.getParent().toString() : "")
                    .fileSize(fileSize)
                    .keywords(keywords)
                    .annotation(annotationText)
                    .updateDate(LocalDateTime.now())
                    .build();

        } catch (Exception e) {
            throw new RuntimeException("Помилка парсингу FB2: " + e.getMessage(), e);
        }
    }
}