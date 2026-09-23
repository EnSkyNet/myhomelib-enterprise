package com.myhomelibcorp.converter.toolchain.validation;

import com.myhomelibcorp.converter.toolchain.ConversionFailureKind;
import com.myhomelibcorp.converter.toolchain.ConversionToolchainException;
import com.myhomelibcorp.shared.xml.SecureXmlInputFactory;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Secure structural minimum for converter-produced FB2. */
@Component
public class Fb2OutputValidator implements ConversionOutputValidator {

    @Override public boolean supports(String format) { return "fb2".equalsIgnoreCase(format); }

    @Override public void validate(Path file, long maxOutputBytes) throws ConversionToolchainException {
        ValidationSupport.validateRegularFile(file, maxOutputBytes);
        try (InputStream in = Files.newInputStream(file, LinkOption.NOFOLLOW_LINKS)) {
            XMLStreamReader reader = SecureXmlInputFactory.create(false, false).createXMLStreamReader(in);
            try {
                boolean rootSeen = false;
                boolean descriptionSeen = false;
                boolean titleInfoSeen = false;
                boolean bodySeen = false;
                boolean bookTitleSeen = false;
                int depth = 0;
                int titleInfoDepth = -1;
                while (reader.hasNext()) {
                    int event = reader.next();
                    if (event == XMLStreamConstants.START_ELEMENT) {
                        depth++;
                        String local = reader.getLocalName();
                        if (!rootSeen) {
                            if (!"FictionBook".equals(local)) {
                                throw invalid("FB2 root element is not FictionBook");
                            }
                            rootSeen = true;
                            continue;
                        }
                        if (depth == 2 && "description".equals(local)) descriptionSeen = true;
                        if ("title-info".equals(local)) {
                            titleInfoSeen = true;
                            titleInfoDepth = depth;
                        } else if (titleInfoDepth > 0 && depth == titleInfoDepth + 1 && "book-title".equals(local)) {
                            String text = reader.getElementText();
                            if (text != null && !text.isBlank()) bookTitleSeen = true;
                            depth--; // getElementText consumed the matching END_ELEMENT.
                        } else if (depth == 2 && "body".equals(local)) {
                            bodySeen = true;
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        if (depth == titleInfoDepth && "title-info".equals(reader.getLocalName())) titleInfoDepth = -1;
                        depth--;
                    }
                }
                if (!rootSeen) throw invalid("FB2 has no root element");
                if (!descriptionSeen || !titleInfoSeen) throw invalid("FB2 description/title-info is missing");
                if (!bookTitleSeen) throw invalid("FB2 book-title is missing or blank");
                if (!bodySeen) throw invalid("FB2 body is missing");
            } finally {
                reader.close();
            }
        } catch (ConversionToolchainException e) {
            throw e;
        } catch (Exception e) {
            throw new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID, "FB2 output cannot be parsed", e);
        }
    }

    private ConversionToolchainException invalid(String message) {
        return new ConversionToolchainException(ConversionFailureKind.OUTPUT_INVALID, message);
    }
}
