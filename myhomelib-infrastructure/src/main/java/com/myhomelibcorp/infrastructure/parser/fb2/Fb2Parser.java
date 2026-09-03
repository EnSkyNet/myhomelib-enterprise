package com.myhomelibcorp.infrastructure.parser.fb2;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.infrastructure.parser.author.LocalAuthorNameParser;
import lombok.extern.slf4j.Slf4j;

import javax.xml.stream.XMLInputFactory;
import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * Єдиний парсер FB2, який збирає всі метадані за один прохід.
 * Замінює окремі парсери для title, authors, genres, annotation, sequence, language.
 */
@Slf4j
public class Fb2Parser {

    private final XMLInputFactory xmlFactory = SecureXmlInputFactory.create(false, false);


    /**
     * Результат парсингу — всі метадані книги.
     */
    public static class ParseResult {
        private final String title;
        private final List<Author> authors;
        private final List<Genre> genres;
        private final String annotation;
        private final String series;
        private final int sequenceNumber;
        private final String language;

        public ParseResult(String title, List<Author> authors, List<Genre> genres,
                           String annotation, String series, int sequenceNumber, String language) {
            this.title = title;
            this.authors = authors;
            this.genres = genres;
            this.annotation = annotation;
            this.series = series;
            this.sequenceNumber = sequenceNumber;
            this.language = language;
        }

        public String getTitle() { return title; }
        public List<Author> getAuthors() { return authors; }
        public List<Genre> getGenres() { return genres; }
        public String getAnnotation() { return annotation; }
        public String getSeries() { return series; }
        public int getSequenceNumber() { return sequenceNumber; }
        public String getLanguage() { return language; }
    }

    /**
     * Парсить FB2 файл за один прохід.
     */
    public ParseResult parse(InputStream inputStream) throws Exception {
        String title = "Без назви";
        List<Author> authors = new ArrayList<>();
        List<Genre> genres = new ArrayList<>();
        StringBuilder annotation = new StringBuilder();
        String series = "";
        int sequenceNumber = 0;
        String language = "ru";

        XMLStreamReader reader = xmlFactory.createXMLStreamReader(inputStream);

        boolean inTitleInfo = false;
        boolean inAnnotation = false;
        boolean inAuthor = false;
        String firstName = "", middleName = "", lastName = "";

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String localName = reader.getLocalName();

                if ("title-info".equalsIgnoreCase(localName)) {
                    inTitleInfo = true;
                }

                if (inTitleInfo) {
                    if ("author".equalsIgnoreCase(localName)) {
                        inAuthor = true;
                        firstName = middleName = lastName = "";
                    } else if ("book-title".equalsIgnoreCase(localName)) {
                        String text = reader.getElementText().trim();
                        if (!text.isEmpty()) {
                            title = text;
                        }
                    } else if ("genre".equalsIgnoreCase(localName)) {
                        String code = reader.getElementText().trim();
                        if (!code.isEmpty()) {
                            genres.add(new Genre(code, code));
                        }
                    } else if ("annotation".equalsIgnoreCase(localName)) {
                        inAnnotation = true;
                    } else if ("sequence".equalsIgnoreCase(localName)) {
                        String seqName = reader.getAttributeValue(null, "name");
                        if (seqName != null && !seqName.isBlank()) {
                            series = seqName.trim();
                        }
                        String numAttr = reader.getAttributeValue(null, "number");
                        if (numAttr != null && !numAttr.isBlank()) {
                            try {
                                sequenceNumber = Integer.parseInt(numAttr.trim());
                            } catch (NumberFormatException ignored) {}
                        }
                    } else if ("lang".equalsIgnoreCase(localName)) {
                        String lang = reader.getElementText().trim().toLowerCase();
                        if (lang.matches("[a-z]{2}(-[A-Z]{2})?")) {
                            language = lang;
                        }
                    } else if (inAuthor) {
                        if ("first-name".equalsIgnoreCase(localName)) {
                            firstName = reader.getElementText().trim();
                        } else if ("middle-name".equalsIgnoreCase(localName)) {
                            middleName = reader.getElementText().trim();
                        } else if ("last-name".equalsIgnoreCase(localName)) {
                            lastName = reader.getElementText().trim();
                        }
                    }
                }
            }

            if (event == XMLStreamConstants.CHARACTERS) {
                if (inAnnotation) {
                    String text = reader.getText();
                    if (text != null && !text.isBlank()) {
                        annotation.append(text);
                    }
                }
            }

            if (event == XMLStreamConstants.END_ELEMENT) {
                String localName = reader.getLocalName();

                if (inTitleInfo) {
                    if ("author".equalsIgnoreCase(localName) && inAuthor) {
                        if (!lastName.isEmpty() || !firstName.isEmpty() || !middleName.isEmpty()) {
                            authors.addAll(LocalAuthorNameParser.fromStructured(firstName, middleName, lastName));
                        }
                        inAuthor = false;
                    } else if ("annotation".equalsIgnoreCase(localName)) {
                        inAnnotation = false;
                    } else if ("title-info".equalsIgnoreCase(localName)) {
                        inTitleInfo = false;
                        break; // виходимо з циклу, коли закінчився title-info
                    }
                }
            }
        }

        reader.close();

        if (authors.isEmpty()) {
            log.warn("Автори не знайдені, додаємо 'Невідомий Автор'");
            authors.add(new Author(AuthorId.generate(), "", "", "Невідомий Автор"));
        }

        return new ParseResult(
                title,
                authors,
                genres,
                annotation.toString().trim(),
                series,
                sequenceNumber,
                language
        );
    }
}