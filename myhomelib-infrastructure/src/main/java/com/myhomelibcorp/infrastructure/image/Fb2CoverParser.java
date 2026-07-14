package com.myhomelibcorp.infrastructure.image;

import javafx.scene.image.Image;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
@Slf4j
public class Fb2CoverParser {

    private static final int DEFAULT_COVER_WIDTH = 180;
    private static final int DEFAULT_COVER_HEIGHT = 250;
    private static final int MAX_IMAGE_SIZE = 10 * 1024 * 1024;

    private final XMLInputFactory xmlFactory = XMLInputFactory.newInstance();

    public Fb2CoverParser() {
        xmlFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xmlFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    public Image parse(InputStream inputStream) {
        log.debug("▶️ Fb2CoverParser.parse() викликано");

        Charset[] charsets = {StandardCharsets.UTF_8, Charset.forName("windows-1251"), Charset.forName("cp866")};

        for (Charset charset : charsets) {
            try {
                log.debug("Спроба парсингу з кодуванням: {}", charset);
                try (Reader reader = new InputStreamReader(inputStream, charset)) {
                    XMLStreamReader xmlReader = xmlFactory.createXMLStreamReader(reader);
                    Image result = parseCoverFromXml(xmlReader);
                    if (result != null) {
                        log.debug("Обкладинку знайдено з кодуванням: {}", charset);
                        return result;
                    }
                }
            } catch (Exception e) {
                log.trace("Помилка з кодуванням {}: {}", charset, e.getMessage());
            }
        }

        log.debug("Обкладинку не знайдено жодним кодуванням");
        return null;
    }

    public Image parseFromZipEntry(ZipFile zip, ZipEntry entry) {
        log.debug("Парсимо FB2 з entry: {}", entry.getName());
        try (InputStream is = zip.getInputStream(entry)) {
            return parse(is);
        } catch (Exception e) {
            log.error("Помилка парсингу FB2 з entry", e);
            return null;
        }
    }

    public Image loadImageFromEntry(ZipFile zip, ZipEntry entry) {
        try (InputStream is = zip.getInputStream(entry)) {
            byte[] bytes = is.readAllBytes();
            if (bytes.length > MAX_IMAGE_SIZE) {
                log.debug("Зображення завелике: {} байт", bytes.length);
                return null;
            }
            try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes)) {
                Image img = new Image(bis, DEFAULT_COVER_WIDTH, DEFAULT_COVER_HEIGHT, true, true);
                return img.isError() ? null : img;
            }
        } catch (Exception e) {
            log.trace("Не вдалося завантажити зображення з entry: {}", entry.getName(), e);
            return null;
        }
    }

    public Image parseImageOnly(InputStream inputStream) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(inputStream.readAllBytes())) {
            return new Image(bis, DEFAULT_COVER_WIDTH, DEFAULT_COVER_HEIGHT, true, true);
        } catch (Exception e) {
            log.trace("Failed to parse image", e);
            return null;
        }
    }

    private Image parseCoverFromXml(XMLStreamReader xmlReader) throws Exception {
        String coverId = null;
        String binaryContent = null;
        boolean inCoverpage = false;
        String lastGoodBinary = null;
        String lastGoodContentType = null;

        while (xmlReader.hasNext()) {
            int event = xmlReader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String localName = xmlReader.getLocalName().toLowerCase();
                String fullName = xmlReader.getName().toString();

                if ("coverpage".equalsIgnoreCase(localName) || "cover-page".equalsIgnoreCase(localName)) {
                    inCoverpage = true;
                }

                if ("image".equalsIgnoreCase(localName) || "img".equalsIgnoreCase(localName) || fullName.contains("image")) {
                    String href = xmlReader.getAttributeValue("http://www.w3.org/1999/xlink", "href");
                    if (href == null) {
                        href = xmlReader.getAttributeValue(null, "href");
                    }
                    if (href != null && href.startsWith("#")) {
                        coverId = href.substring(1);
                    }
                }

                if ("binary".equalsIgnoreCase(localName)) {
                    String id = xmlReader.getAttributeValue(null, "id");
                    String contentType = xmlReader.getAttributeValue(null, "content-type");
                    String content = null;
                    try {
                        content = xmlReader.getElementText().trim();
                    } catch (Exception e) {
                        // не вдалося прочитати – ігноруємо
                    }
                    if (content != null && !content.isEmpty()) {
                        lastGoodBinary = content;
                        lastGoodContentType = contentType;
                        if (id != null && id.equals(coverId)) {
                            binaryContent = content;
                            break;
                        }
                        if ((coverId == null || coverId.isEmpty()) && contentType != null && contentType.startsWith("image/")) {
                            binaryContent = content;
                        }
                        if (inCoverpage && id != null && contentType != null && contentType.startsWith("image/")) {
                            binaryContent = content;
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

        if (binaryContent == null && lastGoodBinary != null) {
            binaryContent = lastGoodBinary;
        }

        if (binaryContent == null || binaryContent.isEmpty()) {
            return null;
        }

        try {
            String cleanBase64 = binaryContent.replaceAll("\\s+", "");
            byte[] imageBytes = Base64.getDecoder().decode(cleanBase64);
            if (imageBytes.length > MAX_IMAGE_SIZE) {
                return null;
            }
            try (ByteArrayInputStream bis = new ByteArrayInputStream(imageBytes)) {
                Image img = new Image(bis, DEFAULT_COVER_WIDTH, DEFAULT_COVER_HEIGHT, true, true);
                return img.isError() ? null : img;
            }
        } catch (Exception e) {
            return null;
        }
    }
}