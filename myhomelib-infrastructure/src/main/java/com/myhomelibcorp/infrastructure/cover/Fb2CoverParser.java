package com.myhomelibcorp.infrastructure.cover;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.Charset;

@Component
@RequiredArgsConstructor
@Slf4j
public class Fb2CoverParser {

    private final CharsetDetector charsetDetector;
    private final BinaryImageExtractor binaryExtractor;
    private final ImageLoader imageLoader;

    private final XMLInputFactory xmlFactory = XMLInputFactory.newInstance();

    {
        xmlFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xmlFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    public javafx.scene.image.Image parse(InputStream inputStream) {
        try {
            Charset charset = charsetDetector.detect(inputStream);
            try (Reader reader = new java.io.InputStreamReader(inputStream, charset)) {
                XMLStreamReader xmlReader = xmlFactory.createXMLStreamReader(reader);

                String coverId = null;
                String binaryContent = null;
                boolean inCoverpage = false;

                while (xmlReader.hasNext()) {
                    int event = xmlReader.next();

                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String localName = xmlReader.getLocalName().toLowerCase();

                        if ("coverpage".equalsIgnoreCase(localName) || "cover-page".equalsIgnoreCase(localName)) {
                            inCoverpage = true;
                        }

                        if (inCoverpage || true) {
                            if (localName.equals("image") || localName.equals("img")) {
                                String href = xmlReader.getAttributeValue("http://www.w3.org/1999/xlink", "href");
                                if (href == null) {
                                    href = xmlReader.getAttributeValue(null, "href");
                                }
                                if (href != null && href.startsWith("#")) {
                                    coverId = href.substring(1);
                                }
                            }

                            if (localName.equals("binary")) {
                                String id = xmlReader.getAttributeValue(null, "id");
                                String contentType = xmlReader.getAttributeValue(null, "content-type");

                                if (id != null && id.equals(coverId)) {
                                    binaryContent = xmlReader.getElementText();
                                    break;
                                }

                                if (contentType != null && contentType.startsWith("image/") && (coverId == null || coverId.isEmpty())) {
                                    coverId = id;
                                    binaryContent = xmlReader.getElementText();
                                    break;
                                }
                            }
                        }
                    } else if (event == XMLStreamConstants.END_ELEMENT) {
                        String localName = xmlReader.getLocalName().toLowerCase();
                        if ("coverpage".equalsIgnoreCase(localName) || "cover-page".equalsIgnoreCase(localName)) {
                            inCoverpage = false;
                        }
                    }
                }

                xmlReader.close();

                if (binaryContent != null) {
                    byte[] imageBytes = binaryExtractor.extractFromBase64(binaryContent);
                    if (imageBytes != null) {
                        return imageLoader.loadFromBytes(imageBytes);
                    }
                }

            } catch (Exception e) {
                log.trace("Failed to parse FB2", e);
            }
        } catch (Exception e) {
            log.trace("Failed to read FB2", e);
        }
        return null;
    }
}