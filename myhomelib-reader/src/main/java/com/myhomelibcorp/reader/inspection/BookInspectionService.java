package com.myhomelibcorp.reader.inspection;

import com.myhomelibcorp.reader.api.BookFormat;
import com.myhomelibcorp.reader.api.BookParser;
import com.myhomelibcorp.reader.api.BookSource;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ParseOptions;
import com.myhomelibcorp.reader.api.ResourceInfo;
import com.myhomelibcorp.reader.api.TextStorage;
import com.myhomelibcorp.reader.api.TocEntry;
import com.myhomelibcorp.reader.core.registry.DefaultBookFormatRegistry;
import com.myhomelibcorp.reader.format.epub.EpubFormat;
import com.myhomelibcorp.reader.format.fb2.Fb2Format;
import com.myhomelibcorp.reader.format.txt.TxtFormat;
import com.myhomelibcorp.reader.format.zip.ZipFormat;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * UI-neutral, bounded inspection of one local book. Full reader documents are
 * kept only for the lifetime of the returned session so image resources can be
 * opened lazily instead of copied into one large heap object.
 */
public final class BookInspectionService {

    private static final int TOC_PREVIEW_LIMIT = 60;
    private static final int WORD_COUNT_CHUNK = 64 * 1024;

    private final DefaultBookFormatRegistry registry;

    public BookInspectionService() {
        registry = new DefaultBookFormatRegistry();
        registry.register(new Fb2Format());
        registry.register(new EpubFormat());
        registry.register(new TxtFormat());
        registry.register(new ZipFormat());
    }

    public DocumentInspectionSession inspect(BookSource source) {
        if (source == null || !source.exists()) {
            return simple(DocumentInspection.unsupported("", "Локальний файл недоступний"));
        }

        Optional<BookFormat> format = registry.findFormat(source);
        if (format.isEmpty()) {
            return simple(BinaryMetadataInspector.inspect(source));
        }

        ReaderDocument document = null;
        try {
            BookParser parser = format.get().createParser();
            document = parser.parse(source, new ParseOptions(true, false, true, 24 * 1024 * 1024, null));
            if (document == null || document.isEmpty()) {
                closeDocument(document);
                return simple(DocumentInspection.unsupported(source.extension().toUpperCase(Locale.ROOT),
                        "Документ не містить читабельного тексту"));
            }

            long words = countWords(document.text());
            List<TocPreviewEntry> toc = flattenToc(document.toc() != null ? document.toc().entries() : List.of());
            List<DocumentImageInfo> images = imageInfo(document);
            String sourceLanguage = isFb2(source) ? scanFb2SourceLanguage(source) : "";
            var metadata = document.metadata();
            DocumentInspection inspection = new DocumentInspection(
                    true,
                    source.extension().toUpperCase(Locale.ROOT),
                    metadata.title(),
                    metadata.authors(),
                    metadata.language(),
                    sourceLanguage,
                    metadata.publisher(),
                    metadata.year(),
                    metadata.isbn(),
                    metadata.annotation(),
                    document.totalTextLength(),
                    words,
                    document.chapters() != null ? document.chapters().size() : 0,
                    toc,
                    images,
                    ""
            );
            return new ParsedSession(document, inspection);
        } catch (Exception e) {
            closeDocument(document);
            return simple(DocumentInspection.unsupported(source.extension().toUpperCase(Locale.ROOT),
                    "Не вдалося проаналізувати документ: " + safeMessage(e)));
        }
    }

    private static long countWords(TextStorage text) {
        if (text == null || text.length() <= 0) return 0;
        long words = 0;
        boolean inWord = false;
        int length = text.length();
        for (int start = 0; start < length; start += WORD_COUNT_CHUNK) {
            int end = Math.min(length, start + WORD_COUNT_CHUNK);
            String chunk = text.getText(start, end);
            if (chunk == null || chunk.isEmpty()) continue;
            for (int i = 0; i < chunk.length();) {
                int cp = chunk.codePointAt(i);
                boolean word = Character.isLetterOrDigit(cp) || cp == '\'' || cp == 0x2019;
                if (word && !inWord) words++;
                inWord = word;
                i += Character.charCount(cp);
            }
        }
        return words;
    }

    private static List<TocPreviewEntry> flattenToc(List<TocEntry> roots) {
        if (roots == null || roots.isEmpty()) return List.of();
        List<TocPreviewEntry> out = new ArrayList<>();
        for (TocEntry entry : roots) {
            appendToc(entry, out);
            if (out.size() >= TOC_PREVIEW_LIMIT) break;
        }
        return List.copyOf(out);
    }

    private static void appendToc(TocEntry entry, List<TocPreviewEntry> out) {
        if (entry == null || out.size() >= TOC_PREVIEW_LIMIT) return;
        String title = entry.title() == null ? "" : entry.title().trim();
        if (!title.isBlank()) out.add(new TocPreviewEntry(title, Math.max(0, entry.level()), entry.textOffset()));
        if (entry.children() != null) {
            for (TocEntry child : entry.children()) {
                appendToc(child, out);
                if (out.size() >= TOC_PREVIEW_LIMIT) break;
            }
        }
    }

    private static List<DocumentImageInfo> imageInfo(ReaderDocument document) {
        if (document == null || document.resources() == null) return List.of();
        List<DocumentImageInfo> result = new ArrayList<>();
        for (String id : document.resources().getAllIds()) {
            if (id == null) continue;
            Optional<ResourceInfo> info = document.resources().getInfo(id);
            if (info.isPresent() && info.get().isImage()) {
                result.add(new DocumentImageInfo(id, info.get().mimeType(), Math.max(0, info.get().length())));
            }
        }
        return List.copyOf(result);
    }

    private static boolean isFb2(BookSource source) {
        String ext = source.extension().toLowerCase(Locale.ROOT);
        return ext.equals("fb2") || ext.equals("fbd");
    }

    private static String scanFb2SourceLanguage(BookSource source) {
        XMLInputFactory factory = XMLInputFactory.newFactory();
        try {
            factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
            factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        } catch (Exception ignored) {
        }
        try (InputStream in = source.openStream()) {
            XMLStreamReader reader = factory.createXMLStreamReader(in);
            int events = 0;
            while (reader.hasNext() && events++ < 200_000) {
                int event = reader.next();
                if (event == XMLStreamConstants.START_ELEMENT) {
                    String local = reader.getLocalName();
                    if ("src-lang".equalsIgnoreCase(local)) {
                        String value = reader.getElementText();
                        return value == null ? "" : value.trim();
                    }
                    if ("body".equalsIgnoreCase(local)) break;
                }
            }
            reader.close();
        } catch (Exception ignored) {
        }
        return "";
    }

    private static DocumentInspectionSession simple(DocumentInspection inspection) {
        return new DocumentInspectionSession() {
            @Override public DocumentInspection inspection() { return inspection; }
            @Override public Optional<InputStream> openImage(String id) { return Optional.empty(); }
            @Override public void close() { }
        };
    }

    private static final class ParsedSession implements DocumentInspectionSession {
        private ReaderDocument document;
        private final DocumentInspection inspection;

        private ParsedSession(ReaderDocument document, DocumentInspection inspection) {
            this.document = document;
            this.inspection = inspection;
        }

        @Override public DocumentInspection inspection() { return inspection; }

        @Override
        public Optional<InputStream> openImage(String id) {
            ReaderDocument current = document;
            if (current == null || current.resources() == null || id == null) return Optional.empty();
            return current.resources().open(id);
        }

        @Override
        public void close() {
            ReaderDocument current = document;
            document = null;
            closeDocument(current);
        }
    }

    private static void closeDocument(ReaderDocument document) {
        if (document != null && document.resources() instanceof AutoCloseable closeable) {
            try { closeable.close(); } catch (Exception ignored) { }
        }
    }

    private static String safeMessage(Exception e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }
}
