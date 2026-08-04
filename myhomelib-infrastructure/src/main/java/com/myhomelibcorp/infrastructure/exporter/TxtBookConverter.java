package com.myhomelibcorp.infrastructure.exporter;

import com.myhomelibcorp.application.port.out.exporter.BookConverter;
import com.myhomelibcorp.domain.model.book.Book;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;

@Component
@Slf4j
public class TxtBookConverter implements BookConverter {

    private static final String DEFAULT_ENCODING = "UTF-8";
    private final XMLInputFactory xmlFactory = XMLInputFactory.newInstance();

    public TxtBookConverter() {
        xmlFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xmlFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    @Override
    public boolean supports(Book book) {
        return book.getFileName().toLowerCase().endsWith(".fb2");
    }

    @Override
    public String getTargetExtension() {
        return ".txt";
    }

    @Override
    public String getFormatName() {
        return "TXT";
    }

    @Override
    public void convert(Book book, InputStream sourceStream, Path targetFile) throws Exception {
        log.debug("Конвертація FB2 -> TXT: {}", book.getTitle());

        try (OutputStream outputStream = Files.newOutputStream(targetFile)) {
            XMLStreamReader reader = xmlFactory.createXMLStreamReader(sourceStream, DEFAULT_ENCODING);
            StringBuilder text = new StringBuilder();

            // Додаємо заголовок книги
            text.append(book.getTitle()).append("\n");
            text.append("=".repeat(book.getTitle().length())).append("\n\n");
            text.append("Автори: ").append(book.authorsText()).append("\n\n");

            boolean inBody = false;
            boolean inParagraph = false;

            while (reader.hasNext()) {
                int event = reader.next();

                if (event == XMLStreamConstants.START_ELEMENT) {
                    String localName = reader.getLocalName();
                    if ("body".equalsIgnoreCase(localName)) {
                        inBody = true;
                    }
                    if (inBody && "p".equalsIgnoreCase(localName)) {
                        inParagraph = true;
                    }
                    if (inBody && ("title".equalsIgnoreCase(localName) || "subtitle".equalsIgnoreCase(localName))) {
                        text.append("\n");
                    }
                    if (inBody && "empty-line".equalsIgnoreCase(localName)) {
                        text.append("\n");
                    }
                }

                if (event == XMLStreamConstants.CHARACTERS) {
                    if (inParagraph) {
                        String content = reader.getText().trim();
                        if (!content.isEmpty()) {
                            text.append(content);
                        }
                    }
                }

                if (event == XMLStreamConstants.END_ELEMENT) {
                    String localName = reader.getLocalName();
                    if (inBody && "p".equalsIgnoreCase(localName)) {
                        inParagraph = false;
                        text.append("\n\n");
                    }
                    if ("body".equalsIgnoreCase(localName)) {
                        inBody = false;
                    }
                    if ("section".equalsIgnoreCase(localName)) {
                        text.append("\n");
                    }
                }
            }

            outputStream.write(text.toString().getBytes(StandardCharsets.UTF_8));
            log.info("TXT створено: {}", targetFile);
        }
    }
}