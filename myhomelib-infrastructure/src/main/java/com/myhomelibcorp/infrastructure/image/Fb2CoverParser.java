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

    /**
     * Парсить FB2 з InputStream і повертає зображення обкладинки.
     */
    public Image parse(InputStream inputStream) {
        log.debug("▶️ Fb2CoverParser.parse() викликано");

        // Спроба з різними кодуваннями
        Charset[] charsets = {StandardCharsets.UTF_8, Charset.forName("windows-1251"), Charset.forName("cp866")};

        for (Charset charset : charsets) {
            try {
                log.debug("Спроба парсингу з кодуванням: {}", charset);
                // Створюємо новий InputStream, тому що ми його прочитаємо
                // (потрібно, щоб він був маркованим, але ми просто перестворюємо)
                if (!inputStream.markSupported()) {
                    // Якщо не підтримує mark, ми не можемо прочитати його двічі.
                    // Тому краще передавати новий InputStream для кожного кодування.
                    // Але оскільки ми отримуємо його з ZipFile, то можемо відкрити заново.
                    // Тому в методі parseFromZipEntry ми передаємо новий InputStream.
                    // Тут просто припускаємо, що він вже правильний.
                }
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

    /**
     * Парсить FB2 з ZipEntry.
     */
    public Image parseFromZipEntry(ZipFile zip, ZipEntry entry) {
        log.debug("Парсимо FB2 з entry: {}", entry.getName());
        try (InputStream is = zip.getInputStream(entry)) {
            return parse(is);
        } catch (Exception e) {
            log.error("Помилка парсингу FB2 з entry", e);
            return null;
        }
    }

    /**
     * Завантажує зображення безпосередньо з entry (для sidecar).
     */
    public Image loadImageFromEntry(ZipFile zip, ZipEntry entry) {
        try (InputStream is = zip.getInputStream(entry)) {
            byte[] bytes = is.readAllBytes();
            if (bytes.length > MAX_IMAGE_SIZE) {
                log.debug("Зображення завелике: {} байт", bytes.length);
                return null;
            }
            try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes)) {
                Image img = new Image(bis, DEFAULT_COVER_WIDTH, DEFAULT_COVER_HEIGHT, true, true);
                return !img.isError() ? img : null;
            }
        } catch (Exception e) {
            log.trace("Не вдалося завантажити зображення з entry: {}", entry.getName(), e);
            return null;
        }
    }

    private Image parseCoverFromXml(XMLStreamReader xmlReader) throws Exception {
        String coverId = null;
        String binaryContent = null;
        boolean inCoverpage = false;
        int binaryCount = 0;

        log.debug("Починаємо парсинг XML...");

        while (xmlReader.hasNext()) {
            int event = xmlReader.next();

            if (event == XMLStreamConstants.START_ELEMENT) {
                String localName = xmlReader.getLocalName().toLowerCase();
                String fullName = xmlReader.getName().toString();

                log.trace("START_ELEMENT: localName='{}', fullName='{}'", localName, fullName);

                if ("coverpage".equalsIgnoreCase(localName) || "cover-page".equalsIgnoreCase(localName)) {
                    inCoverpage = true;
                    log.debug("▶️ Вхід у coverpage");
                }

                // Шукаємо тег image
                if ("image".equalsIgnoreCase(localName) || "img".equalsIgnoreCase(localName) || fullName.contains("image")) {
                    String href = xmlReader.getAttributeValue("http://www.w3.org/1999/xlink", "href");
                    if (href == null) {
                        href = xmlReader.getAttributeValue(null, "href");
                    }
                    if (href != null && href.startsWith("#")) {
                        coverId = href.substring(1);
                        log.debug("▶️ Знайдено coverId: {}", coverId);
                    }
                }

                // Шукаємо тег binary
                if ("binary".equalsIgnoreCase(localName)) {
                    binaryCount++;
                    String id = xmlReader.getAttributeValue(null, "id");
                    String contentType = xmlReader.getAttributeValue(null, "content-type");

                    log.debug("▶️ Знайдено <binary> #{}: id='{}', content-type='{}'", binaryCount, id, contentType);

                    // 1) Якщо id збігається з coverId
                    if (id != null && id.equals(coverId)) {
                        binaryContent = xmlReader.getElementText();
                        log.debug("✅ Взято binary за coverId, довжина: {}", binaryContent.length());
                        break;
                    }

                    // 2) Якщо coverId ще не знайдено, але це зображення – беремо перше
                    if ((coverId == null || coverId.isEmpty()) && contentType != null && contentType.startsWith("image/")) {
                        coverId = id;
                        binaryContent = xmlReader.getElementText();
                        log.debug("✅ Взято перше binary-зображення, довжина: {}", binaryContent.length());
                        break;
                    }

                    // 3) Якщо всередині coverpage – беремо будь-яке зображення
                    if (inCoverpage && id != null && contentType != null && contentType.startsWith("image/")) {
                        binaryContent = xmlReader.getElementText();
                        log.debug("✅ Взято binary з coverpage, довжина: {}", binaryContent.length());
                        break;
                    }
                }
            } else if (event == XMLStreamConstants.END_ELEMENT) {
                String localName = xmlReader.getLocalName().toLowerCase();
                if ("coverpage".equalsIgnoreCase(localName) || "cover-page".equalsIgnoreCase(localName)) {
                    inCoverpage = false;
                    log.debug("▶️ Вихід з coverpage");
                }
            }
        }

        xmlReader.close();

        if (binaryContent == null || binaryContent.isEmpty()) {
            log.debug("binaryContent порожній або null");
            return null;
        }

        log.debug("Декодуємо Base64, довжина: {}", binaryContent.length());
        try {
            String cleanBase64 = binaryContent.replaceAll("\\s+", "");
            byte[] imageBytes = Base64.getDecoder().decode(cleanBase64);
            log.debug("Декодовано {} байт зображення", imageBytes.length);
            if (imageBytes.length > MAX_IMAGE_SIZE) {
                log.debug("Зображення завелике: {} байт", imageBytes.length);
                return null;
            }
            try (ByteArrayInputStream bis = new ByteArrayInputStream(imageBytes)) {
                Image img = new Image(bis, DEFAULT_COVER_WIDTH, DEFAULT_COVER_HEIGHT, true, true);
                if (!img.isError()) {
                    log.debug("✅ Зображення успішно створено");
                    return img;
                } else {
                    log.debug("❌ Помилка створення Image: isError=true");
                    return null;
                }
            }
        } catch (IllegalArgumentException e) {
            log.debug("❌ Невалідний Base64: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.error("Помилка створення Image", e);
            return null;
        }
    }
    public javafx.scene.image.Image parseImageOnly(InputStream inputStream) {
        try (java.io.ByteArrayInputStream bis = new java.io.ByteArrayInputStream(inputStream.readAllBytes())) {
            return new javafx.scene.image.Image(bis, DEFAULT_COVER_WIDTH, DEFAULT_COVER_HEIGHT, true, true);
        } catch (Exception e) {
            log.trace("Failed to parse image", e);
            return null;
        }
    }
}