package com.myhomelibcorp.infrastructure.exporter;

import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.application.conversion.BookConversionCapability;
import com.myhomelibcorp.application.conversion.BookConversionContext;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;
import com.myhomelibcorp.application.util.SafeFilePublication;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

@Component
@Slf4j
public class TxtBookConverter implements BookConverter {

    private static final String DEFAULT_ENCODING = "UTF-8";
    private final XMLInputFactory xmlFactory = SecureXmlInputFactory.create(false, false);

    @Override public int priority() { return 1000; }

    @Override
    public Set<BookConversionCapability> capabilities() {
        return Set.of(new BookConversionCapability(Set.of("fb2", "txt"), "TXT", ".txt"));
    }

    @Override
    public boolean supports(Book book) {
        String name = book.getArchiveEntry();
        if (name == null || name.isBlank()) name = book.getFileName();
        name = name == null ? "" : name.toLowerCase(Locale.ROOT);
        return name.endsWith(".fb2") || name.endsWith(".fbd") || name.endsWith(".txt") || name.endsWith(".text");
    }

    @Override
    public boolean supports(Book book, String sourceFormat) {
        return Set.of("fb2", "txt").contains(BookConversionCapability.normalizeFormat(sourceFormat));
    }

    @Override public String getTargetExtension() { return ".txt"; }
    @Override public String getFormatName() { return "TXT"; }

    @Override
    public void convert(BookConversionContext context) throws Exception {
        context.checkCancelled();
        convertBySourceFormat(context.book(), context.sourceFormat(), context.sourceStream(), context.targetFile());
        context.checkCancelled();
    }

    @Override
    public void convert(Book book, InputStream sourceStream, Path targetFile) throws Exception {
        convertBySourceFormat(book, legacySourceFormat(book), sourceStream, targetFile);
    }

    private void convertBySourceFormat(Book book, String sourceFormat, InputStream sourceStream, Path targetFile) throws Exception {
        String normalizedSource = BookConversionCapability.normalizeFormat(sourceFormat);
        if ("txt".equals(normalizedSource)) {
            SafeFilePublication.copyFresh(sourceStream, targetFile, "TXT direct-copy output");
            return;
        }
        if (!"fb2".equals(normalizedSource)) {
            throw new IllegalArgumentException("Непідтримуваний вхідний формат для TXT: " + normalizedSource);
        }

        log.debug("Конвертація FB2 -> TXT: {}", book.getTitle());
        try (OutputStream outputStream = SafeFilePublication.openFreshOutputStream(targetFile, "TXT conversion output");
             Writer writer = new BufferedWriter(new OutputStreamWriter(outputStream, StandardCharsets.UTF_8))) {
            XMLStreamReader reader = xmlFactory.createXMLStreamReader(sourceStream, DEFAULT_ENCODING);
            try {
                writeHeader(writer, book);

                boolean inBody = false;
                boolean inParagraph = false;
                TextWhitespaceState whitespace = new TextWhitespaceState();

                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String localName = reader.getLocalName();
                        if ("body".equalsIgnoreCase(localName)) {
                            inBody = true;
                        } else if (inBody && "p".equalsIgnoreCase(localName)) {
                            if (inParagraph) writer.write(System.lineSeparator());
                            inParagraph = true;
                            whitespace.reset();
                        } else if (inBody && ("title".equalsIgnoreCase(localName)
                                || "subtitle".equalsIgnoreCase(localName)
                                || "empty-line".equalsIgnoreCase(localName))) {
                            writer.write(System.lineSeparator());
                        }
                    } else if ((event == XMLStreamConstants.CHARACTERS || event == XMLStreamConstants.CDATA)
                            && inBody && inParagraph) {
                        writeNormalizedText(writer, reader.getText(), whitespace);
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        String localName = reader.getLocalName();
                        if (inBody && "p".equalsIgnoreCase(localName)) {
                            inParagraph = false;
                            writer.write(System.lineSeparator());
                            writer.write(System.lineSeparator());
                            whitespace.reset();
                        } else if ("body".equalsIgnoreCase(localName)) {
                            inBody = false;
                        } else if (inBody && "section".equalsIgnoreCase(localName)) {
                            writer.write(System.lineSeparator());
                        }
                    }
                }
                writer.flush();
            } finally {
                try { reader.close(); } catch (Exception closeFailure) {
                    log.debug("Не вдалося закрити FB2 XML reader", closeFailure);
                }
            }
        }
        log.info("TXT створено: {}", targetFile);
    }


    private static String legacySourceFormat(Book book) {
        String sourceName = book == null ? "" : book.getArchiveEntry();
        if (sourceName == null || sourceName.isBlank()) sourceName = book == null ? "" : book.getFileName();
        sourceName = sourceName == null ? "" : sourceName.toLowerCase(Locale.ROOT);
        if (sourceName.endsWith(".txt") || sourceName.endsWith(".text")) return "txt";
        return "fb2";
    }

    private static void writeHeader(Writer writer, Book book) throws java.io.IOException {
        String title = book.getTitle() == null ? "" : book.getTitle();
        writer.write(title);
        writer.write(System.lineSeparator());
        writer.write("=".repeat(Math.max(0, title.length())));
        writer.write(System.lineSeparator());
        writer.write(System.lineSeparator());
        writer.write("Автори: ");
        writer.write(book.authorsText() == null ? "" : book.authorsText());
        writer.write(System.lineSeparator());
        writer.write(System.lineSeparator());
    }

    /** Collapse XML formatting whitespace without losing boundaries between StAX text chunks. */
    private static void writeNormalizedText(Writer writer, String text, TextWhitespaceState state)
            throws java.io.IOException {
        if (text == null || text.isEmpty()) return;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                if (state.hasText) state.pendingSpace = true;
                continue;
            }
            if (state.pendingSpace) {
                writer.write(' ');
                state.pendingSpace = false;
            }
            writer.write(c);
            state.hasText = true;
        }
    }

    private static final class TextWhitespaceState {
        private boolean hasText;
        private boolean pendingSpace;
        private void reset() { hasText = false; pendingSpace = false; }
    }
}
