package com.myhomelibcorp.reader.format.fb2;

import com.myhomelibcorp.reader.api.*;
import com.myhomelibcorp.reader.core.document.CompactReaderDocument;
import com.myhomelibcorp.reader.core.document.DefaultTableOfContents;
import com.myhomelibcorp.reader.core.resource.SimpleResourceRepository;
import com.myhomelibcorp.reader.core.text.TextStorageImpl;
import lombok.extern.slf4j.Slf4j;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;

@Slf4j
public class Fb2StreamingParser implements BookParser {

    private static final Charset[] CHARSETS = {
            StandardCharsets.UTF_8,
            Charset.forName("Windows-1251"),
            Charset.forName("IBM866"),
            Charset.forName("KOI8-R"),
            Charset.forName("ISO-8859-5")
    };

    private final XMLInputFactory xmlFactory;

    public Fb2StreamingParser() {
        this.xmlFactory = XMLInputFactory.newInstance();
        xmlFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xmlFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        xmlFactory.setProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, false);
        xmlFactory.setProperty(XMLInputFactory.IS_COALESCING, false);
    }

    @Override
    public BookDocumentMetadata readMetadata(BookSource source) throws IOException {
        return null;
    }

    @Override
    public ReaderDocument parse(BookSource source, ParseOptions options) throws IOException {
        log.info("📖 Парсинг FB2: {}", source.name());

        long startTime = System.currentTimeMillis();

        byte[] data;
        try (InputStream is = source.openStream()) {
            data = is.readAllBytes();
        }

        if (data == null || data.length == 0) {
            throw new IOException("Файл порожній");
        }

        Charset detectedCharset = detectCharset(data);
        log.info("🔍 Визначено кодування: {}", detectedCharset);

        try {
            ReaderDocument result = parseWithCharset(data, detectedCharset, options);

            if (result != null && !result.isEmpty()) {
                long elapsed = System.currentTimeMillis() - startTime;
                log.info("✅ FB2 розпарсено: '{}', {} символів, {} розділів, {} зображень ({} мс)",
                        result.metadata().title(),
                        result.totalTextLength(),
                        result.chapters().size(),
                        result.resources().count(),
                        elapsed);
                return result;
            }
        } catch (XMLStreamException e) {
            log.error("Помилка парсингу XML з кодуванням {}: {}", detectedCharset, e.getMessage());
            throw new IOException("Не вдалося розпарсити FB2 (XML помилка): " + e.getMessage(), e);
        }

        throw new IOException("Не вдалося розпарсити FB2");
    }

    private Charset detectCharset(byte[] data) {
        if (data.length >= 3 && data[0] == (byte) 0xEF && data[1] == (byte) 0xBB && data[2] == (byte) 0xBF) {
            log.debug("✅ Знайдено UTF-8 BOM");
            return StandardCharsets.UTF_8;
        }

        Charset bestCharset = StandardCharsets.UTF_8;
        int bestScore = -1;

        for (Charset charset : CHARSETS) {
            try {
                String testContent = new String(data, charset);

                int replacementCount = countReplacementChars(testContent);
                int totalChars = testContent.length();
                double replacementRatio = totalChars > 0 ? (double) replacementCount / totalChars : 1.0;

                boolean hasFictionBook = testContent.contains("FictionBook") || testContent.contains("fictionbook");
                boolean hasTitle = testContent.contains("book-title");
                boolean hasCyrillic = testContent.matches(".*[\\u0400-\\u04FF].*");

                int score = 0;
                if (hasFictionBook) score += 50;
                if (hasTitle) score += 30;
                if (hasCyrillic) score += 20;
                if (replacementRatio < 0.05) score += 10;

                if (replacementRatio > 0.3) {
                    score = -1;
                }

                log.debug("Кодування {}: score={}, replacementRatio={}, hasFictionBook={}, hasTitle={}",
                        charset, score, replacementRatio, hasFictionBook, hasTitle);

                if (score > bestScore) {
                    bestScore = score;
                    bestCharset = charset;
                }

                if (score >= 90) {
                    log.debug("✅ Ідеальне кодування: {}", charset);
                    return charset;
                }

            } catch (Exception e) {
                log.debug("Кодування {} не підходить: {}", charset, e.getMessage());
            }
        }

        log.debug("✅ Обрано кодування: {} (score={})", bestCharset, bestScore);
        return bestCharset;
    }

    private int countReplacementChars(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        int count = 0;
        for (char c : text.toCharArray()) {
            if (c == '\uFFFD') {
                count++;
            }
        }
        return count;
    }

    private ReaderDocument parseWithCharset(byte[] data, Charset charset, ParseOptions options)
            throws XMLStreamException, IOException {

        int offset = 0;
        if (data.length >= 3 && data[0] == (byte) 0xEF && data[1] == (byte) 0xBB && data[2] == (byte) 0xBF) {
            offset = 3;
            log.debug("⏭️ Пропускаємо UTF-8 BOM");
        }

        try (ByteArrayInputStream bais = new ByteArrayInputStream(data, offset, data.length - offset)) {

            XMLStreamReader reader = xmlFactory.createXMLStreamReader(bais, charset.name());

            String title = "Без назви";
            List<String> authors = new ArrayList<>();
            List<String> genres = new ArrayList<>();
            String language = "uk";
            String series = null;
            Integer sequenceNumber = null;
            String annotation = "";

            TextStorageImpl textStorage = new TextStorageImpl();
            SimpleResourceRepository resources = new SimpleResourceRepository();
            DefaultTableOfContents toc = new DefaultTableOfContents();
            List<ChapterIndex> chapters = new ArrayList<>();

            boolean inTitleInfo = false;
            boolean inBody = false;
            boolean inSection = false;
            boolean inAnnotation = false;
            boolean inBinary = false;

            String currentSectionTitle = "Розділ";
            int currentParagraphIndex = 0;
            long currentTextOffset = 0;
            int sectionDepth = 0;

            TextStyle currentStyle = TextStyle.NORMAL;
            boolean inParagraph = false;
            int paragraphStartOffset = 0;

            String currentBinaryId = null;
            String currentBinaryContentType = null;
            StringBuilder currentBinaryData = new StringBuilder();

            while (reader.hasNext()) {
                int event = reader.next();

                switch (event) {
                    case XMLStreamConstants.START_ELEMENT -> {
                        String localName = reader.getLocalName().toLowerCase();

                        if ("title-info".equals(localName) || "src-title-info".equals(localName)) {
                            inTitleInfo = true;
                        }

                        if (inTitleInfo) {
                            switch (localName) {
                                case "book-title" -> {
                                    title = reader.getElementText().trim();
                                    if (title.isEmpty()) title = "Без назви";
                                }
                                case "genre" -> {
                                    String genre = reader.getElementText().trim();
                                    if (!genre.isEmpty()) genres.add(genre);
                                }
                                case "lang" -> {
                                    String lang = reader.getElementText().trim().toLowerCase();
                                    if (lang.matches("[a-z]{2}(-[A-Z]{2})?")) {
                                        language = lang;
                                    }
                                }
                                case "sequence" -> {
                                    series = reader.getAttributeValue(null, "name");
                                    String num = reader.getAttributeValue(null, "number");
                                    if (num != null && !num.isEmpty()) {
                                        try {
                                            sequenceNumber = Integer.parseInt(num);
                                        } catch (NumberFormatException ignored) {}
                                    }
                                }
                                case "annotation" -> inAnnotation = true;
                            }
                        }

                        if ("binary".equals(localName)) {
                            inBinary = true;
                            currentBinaryId = reader.getAttributeValue(null, "id");
                            currentBinaryContentType = reader.getAttributeValue(null, "content-type");
                            currentBinaryData.setLength(0);
                        }

                        if ("body".equals(localName)) {
                            inBody = true;
                        }

                        if (inBody) {
                            if ("section".equals(localName)) {
                                inSection = true;
                                sectionDepth++;
                                String sectionTitle = readSectionTitle(reader);
                                if (sectionTitle != null && !sectionTitle.isEmpty()) {
                                    currentSectionTitle = sectionTitle;
                                }
                                toc.addEntry(currentSectionTitle, currentTextOffset, sectionDepth);
                            }

                            if (("p".equals(localName) || "subtitle".equals(localName) || "title".equals(localName))
                                    && inSection) {
                                inParagraph = true;
                                paragraphStartOffset = textStorage.length();
                                currentStyle = getStyleForTag(localName);
                                if ("title".equals(localName) || "subtitle".equals(localName)) {
                                    textStorage.startParagraph(currentStyle);
                                }
                            }

                            if ("image".equals(localName) && inSection) {
                                String href = reader.getAttributeValue("http://www.w3.org/1999/xlink", "href");
                                if (href == null) {
                                    href = reader.getAttributeValue(null, "href");
                                }
                                if (href != null && href.startsWith("#")) {
                                    String imageId = href.substring(1);
                                    textStorage.append("[IMAGE:" + imageId + "]", TextStyle.NORMAL);
                                }
                            }

                            if ("empty-line".equals(localName)) {
                                textStorage.append("\n", TextStyle.NORMAL);
                            }
                        }
                    }

                    case XMLStreamConstants.CHARACTERS -> {
                        String text = reader.getText();
                        if (text == null || text.isEmpty()) continue;

                        if (inBinary) {
                            currentBinaryData.append(text.trim());
                            continue;
                        }

                        if (inBody && inSection && inParagraph) {
                            textStorage.append(text, currentStyle);
                        } else if (inAnnotation) {
                            annotation += text;
                        }
                    }

                    case XMLStreamConstants.END_ELEMENT -> {
                        String localName = reader.getLocalName().toLowerCase();

                        if (inBinary && "binary".equals(localName)) {
                            inBinary = false;
                            if (currentBinaryId != null && currentBinaryData.length() > 0) {
                                try {
                                    String cleanBase64 = currentBinaryData.toString().replaceAll("\\s+", "");
                                    byte[] imageData = Base64.getDecoder().decode(cleanBase64);
                                    if (imageData.length > 0) {
                                        String mimeType = currentBinaryContentType != null ?
                                                currentBinaryContentType : "image/jpeg";
                                        resources.add(currentBinaryId, mimeType, imageData);
                                        log.debug("🖼️ Збережено зображення: {} ({} байт)",
                                                currentBinaryId, imageData.length);
                                    }
                                } catch (IllegalArgumentException e) {
                                    log.debug("Помилка декодування зображення {}: {}",
                                            currentBinaryId, e.getMessage());
                                }
                            }
                            currentBinaryId = null;
                            currentBinaryContentType = null;
                            currentBinaryData.setLength(0);
                        }

                        if (inTitleInfo && "title-info".equals(localName)) {
                            inTitleInfo = false;
                        }

                        if (inAnnotation && "annotation".equals(localName)) {
                            inAnnotation = false;
                        }

                        if (inBody) {
                            if ("section".equals(localName)) {
                                inSection = false;
                                sectionDepth--;
                                if (currentSectionTitle != null && !currentSectionTitle.isEmpty()) {
                                    chapters.add(new ChapterIndex(
                                            "ch_" + (chapters.size() + 1),
                                            currentSectionTitle,
                                            currentTextOffset,
                                            textStorage.length(),
                                            currentParagraphIndex
                                    ));
                                }
                                currentSectionTitle = "Розділ";
                                currentParagraphIndex = 0;
                            }

                            if (("p".equals(localName) || "subtitle".equals(localName) || "title".equals(localName))
                                    && inSection) {
                                inParagraph = false;
                                if (textStorage.length() > paragraphStartOffset) {
                                    textStorage.endParagraph();
                                    currentParagraphIndex++;
                                }
                            }

                            if ("body".equals(localName)) {
                                inBody = false;
                            }
                        }
                    }
                }
            }

            reader.close();

            if (chapters.isEmpty() && textStorage.length() > 0) {
                chapters.add(new ChapterIndex(
                        "ch_1",
                        "Зміст",
                        0,
                        textStorage.length(),
                        textStorage.getParagraphCount()
                ));
            }

            BookMetadata metadata = new BookMetadata(
                    "",
                    title,
                    authors.isEmpty() ? List.of("Невідомий автор") : authors,
                    language,
                    series,
                    sequenceNumber,
                    genres,
                    annotation,
                    "",
                    "",
                    null,
                    0
            );

            return CompactReaderDocument.builder()
                    .metadata(metadata)
                    .chapters(chapters)
                    .resources(resources)
                    .text(textStorage)
                    .toc(toc)
                    .totalTextLength(textStorage.length())
                    .build();
        }
    }

    private TextStyle getStyleForTag(String tag) {
        return switch (tag) {
            case "title" -> TextStyle.HEADING_1;
            case "subtitle" -> TextStyle.HEADING_2;
            default -> TextStyle.NORMAL;
        };
    }

    private String readSectionTitle(XMLStreamReader reader) throws XMLStreamException {
        int depth = 0;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = reader.getLocalName().toLowerCase();
                if ("title".equals(name)) {
                    StringBuilder title = new StringBuilder();
                    while (reader.hasNext()) {
                        int next = reader.next();
                        if (next == XMLStreamConstants.CHARACTERS) {
                            title.append(reader.getText());
                        } else if (next == XMLStreamConstants.END_ELEMENT && "title".equals(reader.getLocalName().toLowerCase())) {
                            return title.toString().trim();
                        }
                    }
                }
                depth++;
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String name = reader.getLocalName().toLowerCase();
                if ("section".equals(name) && depth == 0) {
                    break;
                }
                depth--;
            }
        }
        return null;
    }
}