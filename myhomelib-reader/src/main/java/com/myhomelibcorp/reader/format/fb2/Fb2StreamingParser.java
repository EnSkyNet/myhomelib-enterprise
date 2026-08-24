package com.myhomelibcorp.reader.format.fb2;

import com.myhomelibcorp.reader.api.BookDocumentMetadata;
import com.myhomelibcorp.reader.api.BookDocumentMetadataSnapshot;
import com.myhomelibcorp.reader.api.BookMetadata;
import com.myhomelibcorp.reader.api.BookParser;
import com.myhomelibcorp.reader.api.BookSource;
import com.myhomelibcorp.reader.api.ChapterIndex;
import com.myhomelibcorp.reader.api.ParseOptions;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.TextStyle;
import com.myhomelibcorp.reader.core.document.CompactReaderDocument;
import com.myhomelibcorp.reader.core.document.DefaultTableOfContents;
import com.myhomelibcorp.reader.core.resource.HybridResourceRepository;
import com.myhomelibcorp.reader.core.text.TextStorageImpl;
import lombok.extern.slf4j.Slf4j;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;

/**
 * Streaming FB2 parser. Не створює byte[] копію всього файлу.
 */
@Slf4j
public class Fb2StreamingParser implements BookParser {

    private static final Charset[] FALLBACK_CHARSETS = {
            StandardCharsets.UTF_8,
            Charset.forName("Windows-1251"),
            Charset.forName("IBM866"),
            Charset.forName("KOI8-R"),
            Charset.forName("ISO-8859-5")
    };

    private final XMLInputFactory xmlFactory;

    public Fb2StreamingParser() {
        xmlFactory = XMLInputFactory.newFactory();
        setFactoryProperty(XMLInputFactory.SUPPORT_DTD, false);
        setFactoryProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        setFactoryProperty(XMLInputFactory.IS_REPLACING_ENTITY_REFERENCES, true);
        setFactoryProperty(XMLInputFactory.IS_COALESCING, false);
    }

    private void setFactoryProperty(String property, Object value) {
        try {
            xmlFactory.setProperty(property, value);
        } catch (IllegalArgumentException ignored) {
            // Деякі StAX implementations можуть не підтримувати всі властивості.
        }
    }

    @Override
    public BookDocumentMetadata readMetadata(BookSource source) throws IOException {
        ReaderDocument document = parse(source, ParseOptions.minimal());
        return new BookDocumentMetadataSnapshot(
                document.metadata(),
                document.totalTextLength(),
                document.resources() != null && document.resources().count() > 0,
                document.chapters().size()
        );
    }

    @Override
    public ReaderDocument parse(BookSource source, ParseOptions options) throws IOException {
        if (source == null) {
            throw new IOException("FB2 source is null");
        }
        ParseOptions effectiveOptions = options != null ? options : ParseOptions.defaultOptions();
        long started = System.currentTimeMillis();

        Exception firstError = null;
        try {
            ReaderDocument document = parseOnce(source, null, effectiveOptions);
            logResult(document, started);
            return document;
        } catch (XMLStreamException e) {
            firstError = e;
            log.debug("FB2 auto encoding failed: {}", e.getMessage());
        }

        for (Charset charset : FALLBACK_CHARSETS) {
            try {
                ReaderDocument document = parseOnce(source, charset, effectiveOptions);
                log.info("FB2 parsed with forced charset {}", charset.name());
                logResult(document, started);
                return document;
            } catch (XMLStreamException | IOException e) {
                if (firstError == null) firstError = e;
                log.debug("FB2 charset {} failed: {}", charset.name(), e.getMessage());
            }
        }

        throw new IOException("Не вдалося розпарсити FB2: " +
                (firstError != null ? firstError.getMessage() : "невідома XML помилка"), firstError);
    }

    private ReaderDocument parseOnce(BookSource source, Charset forcedCharset, ParseOptions options)
            throws IOException, XMLStreamException {

        try (InputStream input = source.openStream()) {
            XMLStreamReader reader = forcedCharset == null
                    ? xmlFactory.createXMLStreamReader(input)
                    : xmlFactory.createXMLStreamReader(input, forcedCharset.name());
            try {
                return readDocument(reader, source, options);
            } finally {
                try {
                    reader.close();
                } catch (XMLStreamException ignored) {
                }
            }
        }
    }

    private ReaderDocument readDocument(XMLStreamReader reader, BookSource source, ParseOptions options)
            throws XMLStreamException {

        String title = "Без назви";
        List<String> authors = new ArrayList<>();
        List<String> genres = new ArrayList<>();
        String language = "uk";
        String series = null;
        Integer sequenceNumber = null;
        String publisher = "";
        String year = "";
        String isbn = null;
        StringBuilder annotation = new StringBuilder();

        TextStorageImpl textStorage = new TextStorageImpl();
        HybridResourceRepository resources = new HybridResourceRepository();
        DefaultTableOfContents toc = new DefaultTableOfContents();
        List<ChapterIndex> chapters = new ArrayList<>();

        boolean inTitleInfo = false;
        boolean inDocumentInfo = false;
        boolean inPublishInfo = false;
        boolean inAnnotation = false;
        boolean inBody = false;
        boolean readableBody = false;
        boolean inSectionTitle = false;
        StringBuilder sectionTitleText = new StringBuilder();

        boolean inAuthor = false;
        String authorFirst = "";
        String authorMiddle = "";
        String authorLast = "";
        String authorNick = "";

        boolean inParagraph = false;
        String paragraphTag = null;
        TextStyle paragraphStyle = TextStyle.NORMAL;
        TextStyle inlineStyle = TextStyle.NORMAL;
        Deque<TextStyle> inlineStyleStack = new ArrayDeque<>();
        boolean lastWasSpace = false;

        Deque<SectionState> sections = new ArrayDeque<>();
        int genericSectionNumber = 0;

        boolean inBinary = false;
        String binaryId = null;
        String binaryContentType = null;
        Path binaryBase64File = null;
        Writer binaryWriter = null;
        boolean binaryTooLarge = false;
        long binaryEncodedChars = 0;
        long maxBase64Chars = options.maxImageSizeBytes() > 0
                ? options.maxImageSizeBytes() * 4L / 3L + 16_384L
                : 0;

        while (reader.hasNext()) {
            int event = reader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String name = lower(reader.getLocalName());

                if ("title-info".equals(name) || "src-title-info".equals(name)) {
                    inTitleInfo = true;
                    continue;
                }
                if ("document-info".equals(name)) {
                    inDocumentInfo = true;
                    continue;
                }
                if ("publish-info".equals(name)) {
                    inPublishInfo = true;
                    continue;
                }

                if (inTitleInfo && !inBody) {
                    switch (name) {
                        case "book-title" -> {
                            String value = safeElementText(reader);
                            if (!value.isBlank()) title = value.trim();
                            continue;
                        }
                        case "genre" -> {
                            String value = safeElementText(reader).trim();
                            if (!value.isEmpty()) genres.add(value);
                            continue;
                        }
                        case "lang" -> {
                            String value = safeElementText(reader).trim().toLowerCase(Locale.ROOT);
                            if (!value.isEmpty()) language = value;
                            continue;
                        }
                        case "sequence" -> {
                            String value = reader.getAttributeValue(null, "name");
                            if (value != null && !value.isBlank()) series = value.trim();
                            String number = reader.getAttributeValue(null, "number");
                            if (number != null) {
                                try {
                                    sequenceNumber = Integer.parseInt(number.trim());
                                } catch (NumberFormatException ignored) {
                                }
                            }
                        }
                        case "author" -> {
                            inAuthor = true;
                            authorFirst = authorMiddle = authorLast = authorNick = "";
                        }
                        case "first-name" -> {
                            if (inAuthor) authorFirst = safeElementText(reader).trim();
                            continue;
                        }
                        case "middle-name" -> {
                            if (inAuthor) authorMiddle = safeElementText(reader).trim();
                            continue;
                        }
                        case "last-name" -> {
                            if (inAuthor) authorLast = safeElementText(reader).trim();
                            continue;
                        }
                        case "nickname" -> {
                            if (inAuthor) authorNick = safeElementText(reader).trim();
                            continue;
                        }
                        case "annotation" -> inAnnotation = true;
                        default -> { }
                    }
                }

                if (inPublishInfo) {
                    switch (name) {
                        case "publisher" -> {
                            publisher = safeElementText(reader).trim();
                            continue;
                        }
                        case "year" -> {
                            year = safeElementText(reader).trim();
                            continue;
                        }
                        case "isbn" -> {
                            String value = safeElementText(reader).trim();
                            isbn = value.isEmpty() ? null : value;
                            continue;
                        }
                        default -> { }
                    }
                }

                if ("binary".equals(name)) {
                    inBinary = true;
                    binaryId = reader.getAttributeValue(null, "id");
                    binaryContentType = reader.getAttributeValue(null, "content-type");
                    binaryTooLarge = false;
                    binaryEncodedChars = 0;
                    binaryBase64File = null;
                    binaryWriter = null;
                    if (!options.loadImages()) {
                        resources.addMetadata(binaryId, binaryContentType);
                    } else {
                        try {
                            binaryBase64File = Files.createTempFile("myhomelib-fb2-b64-", ".tmp");
                            binaryWriter = new OutputStreamWriter(
                                    Files.newOutputStream(binaryBase64File), StandardCharsets.US_ASCII);
                        } catch (IOException e) {
                            binaryTooLarge = true;
                            log.debug("Не вдалося створити temp для FB2 binary {}: {}", binaryId, e.getMessage());
                        }
                    }
                    continue;
                }

                if ("body".equals(name)) {
                    inBody = true;
                    String bodyName = reader.getAttributeValue(null, "name");
                    readableBody = bodyName == null || bodyName.isBlank() || options.loadFootnotes();
                    continue;
                }

                if (!inBody || !readableBody) {
                    continue;
                }

                if ("section".equals(name)) {
                    int level = sections.size() + 1;
                    SectionState state = new SectionState(
                            ++genericSectionNumber,
                            level,
                            textStorage.length(),
                            textStorage.getParagraphCount()
                    );
                    sections.push(state);
                    continue;
                }

                if ("title".equals(name) && !sections.isEmpty()) {
                    inSectionTitle = true;
                    sectionTitleText.setLength(0);
                    continue;
                }

                if (isParagraphTag(name) && !inParagraph) {
                    paragraphTag = name;
                    paragraphStyle = styleForParagraph(name, inSectionTitle, sections.size());
                    textStorage.startParagraph(paragraphStyle);
                    inParagraph = true;
                    lastWasSpace = false;
                    inlineStyle = TextStyle.NORMAL;
                    inlineStyleStack.clear();
                    continue;
                }

                TextStyle inline = inlineStyleFor(name);
                if (inline != null && inParagraph) {
                    inlineStyleStack.push(inlineStyle);
                    inlineStyle = combineInlineStyles(inlineStyle, inline);
                    continue;
                }

                if ("image".equals(name)) {
                    String href = reader.getAttributeValue("http://www.w3.org/1999/xlink", "href");
                    if (href == null) href = reader.getAttributeValue(null, "href");
                    if (href != null && href.startsWith("#")) {
                        if (inParagraph) {
                            appendNormalized(textStorage, " [IMAGE:" + href.substring(1) + "] ", paragraphStyle, false);
                        } else {
                            textStorage.startParagraph(TextStyle.NORMAL);
                            textStorage.append("[IMAGE:" + href.substring(1) + "]", TextStyle.NORMAL);
                            textStorage.endParagraph();
                        }
                    }
                    continue;
                }

                if ("empty-line".equals(name)) {
                    textStorage.startParagraph(TextStyle.NORMAL);
                    textStorage.append(" ", TextStyle.NORMAL);
                    textStorage.endParagraph();
                }
            }

            else if (event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA) {
                String value = reader.getText();
                if (value == null || value.isEmpty()) continue;

                if (inBinary) {
                    if (binaryWriter != null && !binaryTooLarge) {
                        binaryEncodedChars += value.length();
                        if (maxBase64Chars > 0 && binaryEncodedChars > maxBase64Chars) {
                            binaryTooLarge = true;
                            closeQuietly(binaryWriter);
                            binaryWriter = null;
                            deleteQuietly(binaryBase64File);
                            binaryBase64File = null;
                        } else {
                            try {
                                binaryWriter.write(value);
                            } catch (IOException e) {
                                binaryTooLarge = true;
                                closeQuietly(binaryWriter);
                                binaryWriter = null;
                                deleteQuietly(binaryBase64File);
                                binaryBase64File = null;
                            }
                        }
                    }
                    continue;
                }

                if (inAnnotation && !inBody) {
                    appendPlainNormalized(annotation, value);
                    continue;
                }

                if (inBody && readableBody && inParagraph) {
                    TextStyle effectiveStyle = inlineStyle != TextStyle.NORMAL ? inlineStyle : paragraphStyle;
                    lastWasSpace = appendNormalized(textStorage, value, effectiveStyle, lastWasSpace);
                    if (inSectionTitle) {
                        appendPlainNormalized(sectionTitleText, value);
                    }
                }
            }

            else if (event == XMLStreamConstants.END_ELEMENT) {
                String name = lower(reader.getLocalName());

                if ("binary".equals(name) && inBinary) {
                    closeQuietly(binaryWriter);
                    binaryWriter = null;

                    if (binaryBase64File != null && !binaryTooLarge && binaryId != null && !binaryId.isBlank()) {
                        try (InputStream encoded = Files.newInputStream(binaryBase64File);
                             InputStream decoded = Base64.getMimeDecoder().wrap(encoded)) {
                            boolean added = resources.add(
                                    binaryId,
                                    binaryContentType != null ? binaryContentType : "image/jpeg",
                                    decoded,
                                    options.maxImageSizeBytes());
                            if (!added) {
                                log.debug("FB2 resource {} пропущено (порожній або перевищує ліміт)", binaryId);
                            }
                        } catch (IllegalArgumentException | IOException e) {
                            log.debug("Invalid base64 resource {}: {}", binaryId, e.getMessage());
                        } finally {
                            deleteQuietly(binaryBase64File);
                        }
                    } else {
                        deleteQuietly(binaryBase64File);
                    }

                    inBinary = false;
                    binaryId = null;
                    binaryContentType = null;
                    binaryBase64File = null;
                    binaryTooLarge = false;
                    binaryEncodedChars = 0;
                    continue;
                }

                if (inTitleInfo && "author".equals(name) && inAuthor) {
                    String author = buildAuthor(authorFirst, authorMiddle, authorLast, authorNick);
                    if (!author.isBlank()) authors.add(author);
                    inAuthor = false;
                    continue;
                }
                if ("annotation".equals(name) && inAnnotation) {
                    inAnnotation = false;
                }
                if ("title-info".equals(name) || "src-title-info".equals(name)) {
                    inTitleInfo = false;
                } else if ("document-info".equals(name)) {
                    inDocumentInfo = false;
                } else if ("publish-info".equals(name)) {
                    inPublishInfo = false;
                }

                if (inBody && readableBody) {
                    TextStyle inline = inlineStyleFor(name);
                    if (inline != null && inParagraph && !inlineStyleStack.isEmpty()) {
                        inlineStyle = inlineStyleStack.pop();
                        continue;
                    }

                    if (inParagraph && name.equals(paragraphTag)) {
                        textStorage.endParagraph();
                        inParagraph = false;
                        paragraphTag = null;
                        paragraphStyle = TextStyle.NORMAL;
                        inlineStyle = TextStyle.NORMAL;
                        inlineStyleStack.clear();
                        lastWasSpace = false;
                        continue;
                    }

                    if ("title".equals(name) && inSectionTitle && !sections.isEmpty()) {
                        inSectionTitle = false;
                        String parsedTitle = cleanTitle(sectionTitleText.toString());
                        SectionState state = sections.peek();
                        if (!parsedTitle.isBlank()) {
                            state.title = parsedTitle;
                        }
                        addTocIfNeeded(toc, state, options);
                        sectionTitleText.setLength(0);
                        continue;
                    }

                    if ("section".equals(name) && !sections.isEmpty()) {
                        SectionState state = sections.pop();
                        if (!state.tocAdded) addTocIfNeeded(toc, state, options);

                        long endOffset = textStorage.length();
                        if (state.level == 1 && endOffset > state.startOffset) {
                            int paragraphCount = Math.max(0,
                                    textStorage.getParagraphCount() - state.startParagraphIndex);
                            chapters.add(new ChapterIndex(
                                    "ch_" + (chapters.size() + 1),
                                    state.title,
                                    state.startOffset,
                                    endOffset,
                                    paragraphCount
                            ));
                        }
                        continue;
                    }
                }

                if ("body".equals(name) && inBody) {
                    inBody = false;
                    readableBody = false;
                    sections.clear();
                    inSectionTitle = false;
                }
            }
        }

        if (textStorage.length() == 0) {
            throw new XMLStreamException("FB2 не містить тексту body");
        }

        if (chapters.isEmpty()) {
            chapters.add(new ChapterIndex(
                    "ch_1",
                    title != null && !title.isBlank() ? title : "Зміст",
                    0,
                    textStorage.length(),
                    textStorage.getParagraphCount()
            ));
        }

        if (authors.isEmpty()) authors.add("Невідомий автор");
        OptionalLong size = source.size();
        BookMetadata metadata = new BookMetadata(
                source.id(),
                title,
                List.copyOf(authors),
                language,
                series,
                sequenceNumber,
                List.copyOf(genres),
                annotation.toString().trim(),
                publisher,
                year,
                isbn,
                size.isPresent() ? size.getAsLong() : 0
        );

        return CompactReaderDocument.builder()
                .metadata(metadata)
                .chapters(List.copyOf(chapters))
                .resources(resources)
                .text(textStorage)
                .toc(toc)
                .totalTextLength(textStorage.length())
                .build();
    }

    private void addTocIfNeeded(DefaultTableOfContents toc, SectionState state, ParseOptions options) {
        if (state == null || state.tocAdded || !options.buildToc()) return;
        toc.addEntry(state.title, state.startOffset, Math.max(0, state.level - 1));
        state.tocAdded = true;
    }

    private String safeElementText(XMLStreamReader reader) throws XMLStreamException {
        String value = reader.getElementText();
        return value != null ? value : "";
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private boolean isParagraphTag(String tag) {
        return switch (tag) {
            case "p", "subtitle", "v", "text-author" -> true;
            default -> false;
        };
    }

    private TextStyle styleForParagraph(String tag, boolean inTitle, int sectionDepth) {
        if (inTitle) {
            return switch (Math.max(1, Math.min(6, sectionDepth))) {
                case 1 -> TextStyle.HEADING_1;
                case 2 -> TextStyle.HEADING_2;
                case 3 -> TextStyle.HEADING_3;
                case 4 -> TextStyle.HEADING_4;
                case 5 -> TextStyle.HEADING_5;
                default -> TextStyle.HEADING_6;
            };
        }
        return switch (tag) {
            case "subtitle" -> TextStyle.HEADING_2;
            case "v" -> TextStyle.VERSE;
            case "text-author" -> TextStyle.TEXT_AUTHOR;
            default -> TextStyle.NORMAL;
        };
    }

    private TextStyle inlineStyleFor(String tag) {
        return switch (tag) {
            case "strong", "b" -> TextStyle.BOLD;
            case "emphasis", "i" -> TextStyle.ITALIC;
            case "a" -> TextStyle.LINK;
            case "code" -> TextStyle.CODE;
            case "sup" -> TextStyle.SUPERSCRIPT;
            case "sub" -> TextStyle.SUBSCRIPT;
            case "strikethrough", "s" -> TextStyle.STRIKETHROUGH;
            default -> null;
        };
    }

    private TextStyle combineInlineStyles(TextStyle current, TextStyle incoming) {
        TextStyle a = current != null ? current : TextStyle.NORMAL;
        TextStyle b = incoming != null ? incoming : TextStyle.NORMAL;
        if (a == TextStyle.NORMAL) return b;
        if (b == TextStyle.NORMAL || a == b) return a;

        boolean aBold = a == TextStyle.BOLD || a == TextStyle.STRONG || a == TextStyle.BOLD_ITALIC;
        boolean aItalic = a == TextStyle.ITALIC || a == TextStyle.EMPHASIS || a == TextStyle.BOLD_ITALIC;
        boolean bBold = b == TextStyle.BOLD || b == TextStyle.STRONG || b == TextStyle.BOLD_ITALIC;
        boolean bItalic = b == TextStyle.ITALIC || b == TextStyle.EMPHASIS || b == TextStyle.BOLD_ITALIC;
        if ((aBold && bItalic) || (aItalic && bBold)) {
            return TextStyle.BOLD_ITALIC;
        }
        // Для LINK/CODE/SUP/SUB зберігаємо внутрішній стиль; складені span-маски
        // можна додати пізніше без зміни TextStorage API.
        return b;
    }

    /** Повертає стан останнього символу (space/not-space). */
    private boolean appendNormalized(TextStorageImpl storage, String raw, TextStyle style, boolean lastWasSpace) {
        if (raw == null || raw.isEmpty()) return lastWasSpace;
        StringBuilder out = new StringBuilder(raw.length());
        boolean space = lastWasSpace;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!space) {
                    out.append(' ');
                    space = true;
                }
            } else {
                out.append(c);
                space = false;
            }
        }
        if (!out.isEmpty()) storage.append(out.toString(), style);
        return space;
    }

    private void appendPlainNormalized(StringBuilder target, String raw) {
        if (raw == null || raw.isEmpty()) return;
        boolean lastSpace = !target.isEmpty() && Character.isWhitespace(target.charAt(target.length() - 1));
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isWhitespace(c)) {
                if (!lastSpace && !target.isEmpty()) {
                    target.append(' ');
                    lastSpace = true;
                }
            } else {
                target.append(c);
                lastSpace = false;
            }
        }
    }

    private String buildAuthor(String first, String middle, String last, String nick) {
        StringBuilder result = new StringBuilder();
        appendPart(result, first);
        appendPart(result, middle);
        appendPart(result, last);
        if (result.isEmpty()) appendPart(result, nick);
        return result.toString().trim();
    }

    private void appendPart(StringBuilder result, String value) {
        if (value == null || value.isBlank()) return;
        if (!result.isEmpty()) result.append(' ');
        result.append(value.trim());
    }

    private String cleanTitle(String value) {
        if (value == null) return "";
        return value.trim().replaceAll("\\s+", " ");
    }

    private void closeQuietly(Writer writer) {
        if (writer == null) return;
        try {
            writer.close();
        } catch (IOException ignored) {
        }
    }

    private void deleteQuietly(Path path) {
        if (path == null) return;
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
        }
    }

    private void logResult(ReaderDocument document, long started) {
        if (document == null || document.isEmpty()) return;
        log.info("✅ FB2 '{}' — {} chars, {} chapters, {} resources, {} ms",
                document.metadata().title(),
                document.totalTextLength(),
                document.chapters().size(),
                document.resources().count(),
                System.currentTimeMillis() - started);
    }

    private static final class SectionState {
        private final int number;
        private final int level;
        private final long startOffset;
        private final int startParagraphIndex;
        private String title;
        private boolean tocAdded;

        private SectionState(int number, int level, long startOffset, int startParagraphIndex) {
            this.number = number;
            this.level = level;
            this.startOffset = startOffset;
            this.startParagraphIndex = startParagraphIndex;
            this.title = "Розділ " + number;
        }
    }
}
