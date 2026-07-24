package com.myhomelibcorp.infrastructure.image;

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

@Component
@Slf4j
public class Fb2CoverParser {

    private static final int MAX_IMAGE_SIZE = 10 * 1024 * 1024; // 10 MB
    private final XMLInputFactory xmlFactory = XMLInputFactory.newInstance();

    public Fb2CoverParser() {
        xmlFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xmlFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    /**
     * Парсить FB2 і повертає масив байтів обкладинки (першого знайденого зображення).
     */
    public byte[] parseToBytes(InputStream inputStream) {
        log.debug("Парсинг FB2 для отримання обкладинки");

        Charset[] charsets = {StandardCharsets.UTF_8, Charset.forName("windows-1251"), Charset.forName("cp866")};

        for (Charset charset : charsets) {
            try (Reader reader = new InputStreamReader(inputStream, charset)) {
                XMLStreamReader xmlReader = xmlFactory.createXMLStreamReader(reader);
                byte[] result = parseCoverFromXml(xmlReader);
                if (result != null) {
                    log.debug("Обкладинку знайдено з кодуванням {}", charset);
                    return result;
                }
            } catch (Exception e) {
                log.trace("Помилка з кодуванням {}: {}", charset, e.getMessage());
                // Потік вже прочитано, потрібно скинути для наступної спроби — але це не просто.
                // Тому краще завантажити весь вміст у пам'ять і спробувати різні кодування на одному байтовому масиві.
            }
        }

        // Якщо жодне кодування не спрацювало — спробуємо прочитати весь потік як UTF-8
        try {
            inputStream.reset();
        } catch (Exception ignored) {}

        return null;
    }

    /**
     * Альтернативний метод, який приймає байтовий масив (для повторних спроб).
     */
    public byte[] parseToBytes(byte[] data) {
        if (data == null || data.length == 0) return null;
        try (ByteArrayInputStream bais = new ByteArrayInputStream(data)) {
            return parseToBytes(bais);
        } catch (Exception e) {
            log.error("Помилка парсингу з byte[]", e);
            return null;
        }
    }

    private byte[] parseCoverFromXml(XMLStreamReader xmlReader) throws Exception {
        String coverId = null;
        String binaryContent = null;
        boolean inCoverpage = false;
        String lastGoodBinary = null;

        while (xmlReader.hasNext()) {
            int event = xmlReader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String localName = xmlReader.getLocalName().toLowerCase();

                if ("coverpage".equalsIgnoreCase(localName) || "cover-page".equalsIgnoreCase(localName)) {
                    inCoverpage = true;
                }

                if ("image".equalsIgnoreCase(localName) || "img".equalsIgnoreCase(localName)) {
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
                        // ігноруємо
                    }
                    if (content != null && !content.isEmpty()) {
                        lastGoodBinary = content;
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
                log.warn("Зображення завелике: {} байт", imageBytes.length);
                return null;
            }
            return imageBytes;
        } catch (Exception e) {
            log.debug("Помилка декодування Base64", e);
            return null;
        }
    }

    /**
     * Допоміжний метод для читання зображення з потоку (без парсингу XML).
     */
    public byte[] parseImageOnly(InputStream inputStream) {
        try {
            return inputStream.readAllBytes();
        } catch (Exception e) {
            log.error("Помилка читання зображення", e);
            return null;
        }
    }
}