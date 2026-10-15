package com.myhomelibcorp.infrastructure.parser.fb2;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.infrastructure.parser.author.LocalAuthorNameParser;
import lombok.extern.slf4j.Slf4j;

import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Єдиний парсер FB2, який збирає всі метадані за один прохід.
 * Замінює окремі парсери для title, authors, genres, annotation, sequence, language.
 */
@Slf4j
public class Fb2Parser {

    private static final int MAX_METADATA_TEXT_CHARS = 4_096;
    private static final int MAX_ANNOTATION_CHARS = 256 * 1024;


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
        String title = "";
        List<Author> authors = new ArrayList<>();
        List<Genre> genres = new ArrayList<>();
        StringBuilder annotation = new StringBuilder();
        String series = "";
        int sequenceNumber = 0;
        String language = "und";

        XMLStreamReader reader = SecureXmlInputFactory.create(false, false).createXMLStreamReader(inputStream);

        boolean inTitleInfo = false;
        boolean inAnnotation = false;
        boolean inAuthor = false;
        String firstName = "", middleName = "", lastName = "";

        try {
        while (reader.hasNext()) {
            if (Thread.currentThread().isInterrupted()) {
                throw new XMLStreamException("FB2 metadata parsing interrupted");
            }
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
                        String text = readBoundedText(reader, MAX_METADATA_TEXT_CHARS).trim();
                        if (!text.isEmpty()) {
                            title = text;
                        }
                    } else if ("genre".equalsIgnoreCase(localName)) {
                        String code = readBoundedText(reader, MAX_METADATA_TEXT_CHARS).trim();
                        if (!code.isEmpty()) {
                            if (genres.size() < 512) genres.add(new Genre(code, code));
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
                        String lang = readBoundedText(reader, MAX_METADATA_TEXT_CHARS).trim().toLowerCase(Locale.ROOT);
                        if (lang.matches("[a-z]{2}(-[a-z]{2})?")) {
                            language = lang;
                        }
                    } else if (inAuthor) {
                        if ("first-name".equalsIgnoreCase(localName)) {
                            firstName = readBoundedText(reader, MAX_METADATA_TEXT_CHARS).trim();
                        } else if ("middle-name".equalsIgnoreCase(localName)) {
                            middleName = readBoundedText(reader, MAX_METADATA_TEXT_CHARS).trim();
                        } else if ("last-name".equalsIgnoreCase(localName)) {
                            lastName = readBoundedText(reader, MAX_METADATA_TEXT_CHARS).trim();
                        }
                    }
                }
            }

            if (event == XMLStreamConstants.CHARACTERS) {
                if (inAnnotation) {
                    // StAX chunks can be arbitrarily long in malformed files.
                    // Read directly from its character buffer without allocating the full text.
                    int remaining = MAX_ANNOTATION_CHARS - annotation.length();
                    if (remaining > 0) {
                        int length = Math.min(remaining, reader.getTextLength());
                        annotation.append(reader.getTextCharacters(), reader.getTextStart(), length);
                    }
                }
            }

            if (event == XMLStreamConstants.END_ELEMENT) {
                String localName = reader.getLocalName();

                if (inTitleInfo) {
                    if ("author".equalsIgnoreCase(localName) && inAuthor) {
                        if (!lastName.isEmpty() || !firstName.isEmpty() || !middleName.isEmpty()) {
                            if (authors.size() < 512) {
                                authors.addAll(LocalAuthorNameParser.fromStructured(firstName, middleName, lastName));
                            }
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

        } finally {
            reader.close();
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

    /**
     * Consumes a metadata text element without ever building an unbounded String.
     * Excess characters are discarded while the XML cursor advances to its end tag.
     */
    private static String readBoundedText(XMLStreamReader reader, int maximum) throws XMLStreamException {
        StringBuilder text = new StringBuilder(Math.min(maximum, 256));
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.END_ELEMENT) return text.toString();
            if (event == XMLStreamConstants.START_ELEMENT) {
                throw new XMLStreamException("Nested element in simple FB2 metadata text");
            }
            if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA
                    || event == XMLStreamConstants.SPACE || event == XMLStreamConstants.ENTITY_REFERENCE) {
                int available = maximum - text.length();
                if (available > 0 && reader.hasText()) {
                    int length = Math.min(available, reader.getTextLength());
                    text.append(reader.getTextCharacters(), reader.getTextStart(), length);
                }
            }
        }
        throw new XMLStreamException("Unterminated FB2 metadata element");
    }

}
