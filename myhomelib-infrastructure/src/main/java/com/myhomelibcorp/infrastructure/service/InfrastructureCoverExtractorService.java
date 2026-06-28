package com.myhomelibcorp.infrastructure.service;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.CoverExtractor;
import javafx.scene.image.Image;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamReader;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
@Primary
@Slf4j
public class InfrastructureCoverExtractorService implements CoverExtractor {

    private static final int DEFAULT_COVER_WIDTH = 180;
    private static final int DEFAULT_COVER_HEIGHT = 250;
    private static final int MAX_COVER_SIZE = 10 * 1024 * 1024;

    private final XMLInputFactory xmlFactory = XMLInputFactory.newInstance();

    public InfrastructureCoverExtractorService() {
        xmlFactory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        xmlFactory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
    }

    @Override
    public Image extractCover(BookDto book) {
        if (book == null) return null;

        log.info("=== extractCover для: '{}' ===", book.getTitle());

        try {
            Path fullPath = buildFullPath(book);
            if (fullPath == null || !Files.exists(fullPath)) {
                log.warn("Файл не знайдено: {}", fullPath);
                return null;
            }

            String fileName = fullPath.getFileName().toString().toLowerCase();
            boolean isArchive = fileName.endsWith(".zip") || fileName.endsWith(".fb2zip") || fileName.endsWith(".fbd");

            Image cover = isArchive
                    ? extractFromArchive(fullPath, book.getArchiveEntry())
                    : extractFromFb2File(fullPath);

            if (cover != null) {
                log.info("✅ Обкладинку УСПІШНО завантажено");
            } else {
                log.warn("⚠️ Обкладинку НЕ знайдено");
            }
            return cover;
        } catch (Exception e) {
            log.error("Помилка витягування обкладинки", e);
            return null;
        }
    }

    private Path buildFullPath(BookDto book) {
        String folder = book.getFolder();
        if (folder == null || folder.isBlank()) return null;

        Path folderPath = Paths.get(folder);
        if (folderPath.isAbsolute()) return folderPath;

        String collectionRoot = book.getCollectionRoot();
        if (collectionRoot != null && !collectionRoot.isBlank()) {
            return Paths.get(collectionRoot, folder);
        }
        return folderPath;
    }

    private Image extractFromArchive(Path archivePath, String archiveEntry) {
        File file = archivePath.toFile();
        Charset[] charsets = {Charset.forName("CP866"), Charset.forName("Windows-1251"), StandardCharsets.UTF_8};

        for (Charset cs : charsets) {
            try (ZipFile zip = new ZipFile(file, cs)) {
                log.debug("Відкрито архів з кодуванням: {}", cs);
                List<? extends ZipEntry> entries = zip.stream().toList();

                log.debug("Файли в архіві:");
                entries.forEach(e -> log.debug("  - {}", e.getName()));

                // FB2 + binary
                ZipEntry fb2Entry = findFb2Entry(entries);
                if (fb2Entry != null) {
                    Image img = extractCoverFromFb2Entry(zip, fb2Entry);
                    if (img != null) return img;
                }

                // Зображення в архіві
                Image img = searchAnyImageInArchive(zip, entries);
                if (img != null) return img;

            } catch (Exception e) {
                log.trace("Помилка з charset {}", cs);
            }
        }
        return null;
    }

    private ZipEntry findFb2Entry(List<? extends ZipEntry> entries) {
        return entries.stream()
                .filter(e -> !e.isDirectory() && e.getName().toLowerCase().endsWith(".fb2"))
                .findFirst()
                .orElse(null);
    }

    private Image extractCoverFromFb2Entry(ZipFile zip, ZipEntry fb2Entry) {
        log.debug("Парсимо FB2: {}", fb2Entry.getName());

        try (InputStream is = zip.getInputStream(fb2Entry)) {
            try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                XMLStreamReader xml = xmlFactory.createXMLStreamReader(reader);

                String coverId = null;
                String binaryContent = null;

                while (xml.hasNext()) {
                    int event = xml.next();

                    if (event == XMLStreamConstants.START_ELEMENT) {
                        String localName = xml.getLocalName().toLowerCase();
                        String fullName = xml.getName().toString(); // для діагностики

                        if ("image".equals(localName) || "img".equals(localName) || fullName.contains("image")) {
                            String href = xml.getAttributeValue("http://www.w3.org/1999/xlink", "href");
                            if (href == null) href = xml.getAttributeValue(null, "href");
                            if (href != null && href.startsWith("#")) {
                                coverId = href.substring(1);
                                log.debug("Знайдено coverId: {}", coverId);
                            }
                        }

                        if ("binary".equals(localName)) {
                            String id = xml.getAttributeValue(null, "id");
                            String contentType = xml.getAttributeValue(null, "content-type");

                            log.debug("Знайдено <binary> id='{}' content-type='{}'", id, contentType);

                            if (id != null && (coverId == null || id.equalsIgnoreCase(coverId) ||
                                    (contentType != null && contentType.startsWith("image/")))) {
                                binaryContent = xml.getElementText();
                                log.info("✅ Витягнуто binary обкладинку! id={}", id);
                                break;
                            }
                        }
                    }
                }
                xml.close();

                if (binaryContent != null && !binaryContent.isEmpty()) {
                    String clean = binaryContent.replaceAll("\\s+", "");
                    try {
                        byte[] bytes = Base64.getDecoder().decode(clean);
                        log.info("Декодовано {} байт обкладинки", bytes.length);
                        return createImageFromBytes(bytes);
                    } catch (Exception ex) {
                        log.warn("Помилка Base64: {}", ex.getMessage());
                    }
                } else {
                    log.warn("Binary тег з обкладинкою НЕ знайдено в FB2");
                }
            }
        } catch (Exception e) {
            log.error("Критична помилка парсингу FB2", e);
        }
        return null;
    }
    private Image searchAnyImageInArchive(ZipFile zip, List<? extends ZipEntry> entries) {
        log.debug("Запускаємо агресивний пошук зображень в архіві...");

        String[] keywords = {"cover", "oblozhka", "preview", "front", "title", "обложка", "folder", "soldat", "vizit"};

        // 1. Спочатку за ключовими словами
        for (ZipEntry e : entries) {
            if (e.isDirectory()) continue;
            String name = e.getName().toLowerCase();

            for (String kw : keywords) {
                if (name.contains(kw) && isImageFile(name)) {
                    log.info("✅ Знайдено за ключем '{}': {}", kw, e.getName());
                    return loadImageFromEntry(zip, e);
                }
            }
        }

        // 2. Будь-яке зображення в архіві
        for (ZipEntry e : entries) {
            if (!e.isDirectory() && isImageFile(e.getName())) {
                log.info("✅ Знайдено будь-яке зображення: {}", e.getName());
                return loadImageFromEntry(zip, e);
            }
        }

        log.debug("Зображень в архіві не знайдено");
        return null;
    }

    private Image loadImageFromEntry(ZipFile zip, ZipEntry entry) {
        try (InputStream is = zip.getInputStream(entry)) {
            byte[] bytes = is.readAllBytes();
            if (bytes.length > MAX_COVER_SIZE) return null;
            return createImageFromBytes(bytes);
        } catch (Exception e) {
            return null;
        }
    }

    private Image createImageFromBytes(byte[] bytes) {
        try (ByteArrayInputStream bis = new ByteArrayInputStream(bytes)) {
            Image img = new Image(bis, DEFAULT_COVER_WIDTH, DEFAULT_COVER_HEIGHT, true, true);
            return !img.isError() ? img : null;
        } catch (Exception e) {
            return null;
        }
    }

    private Image extractFromFb2File(Path fb2Path) {
        // можна реалізувати пізніше
        return null;
    }

    private boolean isImageFile(String name) {
        if (name == null) return false;
        String n = name.toLowerCase();
        return n.endsWith(".jpg") || n.endsWith(".jpeg") ||
                n.endsWith(".png") || n.endsWith(".gif");
    }
}