package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.dto.BookDto;
import javafx.scene.image.Image;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Base64;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Service
@Slf4j
public class CoverExtractorService {

    private static final int DEFAULT_COVER_WIDTH = 180;
    private static final int DEFAULT_COVER_HEIGHT = 250;

    public Image extractCover(BookDto book) {
        if (book == null) {
            log.warn("extractCover: книга == null");
            return null;
        }

        log.info("=== ПОЧАТОК extractCover для: '{}' ===", book.getTitle());
        log.debug("collectionRoot='{}', folder='{}', fileName='{}', archiveEntry='{}'",
                book.getCollectionRoot(), book.getFolder(), book.getFileName(), book.getArchiveEntry());

        try {
            String fullPath = buildFullPath(book);
            if (fullPath == null) {
                log.error("Не вдалося побудувати шлях до файлу");
                return null;
            }

            Path path = Paths.get(fullPath);
            log.debug("Повний шлях: {}", fullPath);

            if (!Files.exists(path)) {
                log.error("Файл НЕ ЗНАЙДЕНО: {}", fullPath);
                return null;
            }

            String fileNameLower = path.getFileName().toString().toLowerCase();
            boolean isArchive = (book.getArchiveEntry() != null && !book.getArchiveEntry().isBlank()) ||
                    fileNameLower.endsWith(".zip") || fileNameLower.endsWith(".fbd") ||
                    fileNameLower.endsWith(".fb2.zip");

            Image result;
            if (isArchive) {
                log.info("→ Обробка архіву");
                result = extractCoverFromArchive(path, book.getArchiveEntry());
            } else if (fileNameLower.endsWith(".fb2")) {
                log.info("→ Обробка FB2 файлу");
                result = extractCoverFromFb2File(path);
            } else {
                log.info("→ Sidecar обкладинка");
                result = extractFromSidecar(path);
            }

            if (result != null && !result.isError()) {
                log.info("Обкладинку УСПІШНО завантажено");
            } else {
                log.warn("Обкладинку НЕ знайдено (null або error)");
            }
            return result;

        } catch (Exception e) {
            log.error("Критична помилка в extractCover для " + book.getTitle(), e);
            return null;
        }
    }

    /**
     * Будує повний шлях до файлу з використанням collectionRoot або folder + fileName.
     * Додано логування.
     */
    private String buildFullPath(BookDto book) {
        String fileName = book.getFileName() != null ? book.getFileName() : "";
        if (fileName.isBlank()) {
            log.warn("fileName порожній для книги: {}", book.getTitle());
            return null;
        }

        String folder = book.getFolder() != null ? book.getFolder() : "";

        // === ОСНОВНА ЛОГІКА ДЛЯ ZIP ===
        if (book.getArchiveEntry() != null && !book.getArchiveEntry().isBlank()) {
            // Це книга з архіву — повертаємо шлях саме до ZIP-файлу
            if (folder.toLowerCase().endsWith(".zip") ||
                    folder.toLowerCase().endsWith(".fbd") ||
                    folder.toLowerCase().endsWith(".fb2.zip")) {

                log.debug("Книга з архіву → повертаємо шлях до ZIP: {}", folder);
                return folder;
            }
        }

        // Якщо folder вже виглядає як повний шлях до файлу
        if (folder.toLowerCase().endsWith(".fb2") ||
                folder.toLowerCase().endsWith(".zip") ||
                folder.toLowerCase().endsWith(".fbd")) {

            log.debug("folder вже вказує на файл/архів → {}", folder);
            return folder;
        }

        // Звичайний випадок (не в архіві)
        if (book.getCollectionRoot() != null && !book.getCollectionRoot().isBlank()) {
            String full = Paths.get(book.getCollectionRoot(), folder, fileName).toString();
            log.debug("collectionRoot + folder + fileName → {}", full);
            return full;
        }

        log.warn("Fallback: folder + fileName");
        return Paths.get(folder, fileName).toString();
    }

    private Image extractCoverFromArchive(Path archivePath, String archiveEntry) {
        File archiveFile = archivePath.toFile();
        if (!archiveFile.exists()) {
            log.error("Архів не знайдено: {}", archiveFile.getAbsolutePath());
            return null;
        }

        log.info("Відкриваємо архів: {} ({} байт)", archiveFile.getName(), archiveFile.length());

        // Спробуємо різні кодування, як у ZipImporter
        Charset[] charsets = {
                Charset.forName("CP866"),
                Charset.forName("Windows-1251"),
                Charset.forName("UTF-8"),
                Charset.forName("IBM-866")
        };

        for (Charset charset : charsets) {
            try (ZipFile zipFile = new ZipFile(archiveFile, charset)) {
                log.debug("Успішно відкрито архів з кодуванням: {}", charset);

                // 1. Стандартні обкладинки
                String[] coverNames = {"cover.jpg", "cover.jpeg", "cover.png", "folder.jpg", "preview.jpg"};
                for (String name : coverNames) {
                    ZipEntry entry = zipFile.getEntry(name);
                    if (entry != null) {
                        log.info("Знайдено обкладинку: {}", name);
                        try (InputStream is = zipFile.getInputStream(entry)) {
                            Image image = new Image(is, DEFAULT_COVER_WIDTH, DEFAULT_COVER_HEIGHT, true, true);
                            if (!image.isError()) return image;
                        }
                    }
                }

                // 2. По archiveEntry
                if (archiveEntry != null && !archiveEntry.isBlank()) {
                    ZipEntry fb2Entry = zipFile.getEntry(archiveEntry);
                    if (fb2Entry != null) {
                        log.info("Знайдено FB2 за archiveEntry");
                        return extractCoverFromFb2Stream(zipFile, fb2Entry);
                    }
                }

                // 3. Будь-який FB2
                ZipEntry fb2Entry = findFb2Entry(zipFile);
                if (fb2Entry != null) {
                    log.info("Знайдено FB2: {}", fb2Entry.getName());
                    return extractCoverFromFb2Stream(zipFile, fb2Entry);
                }

            } catch (Exception e) {
                log.debug("Не вдалося відкрити з кодуванням {}: {}", charset, e.getMessage());
            }
        }

        log.error("Не вдалося відкрити архів жодним кодуванням");
        return null;
    }

    private Image extractCoverFromFb2Stream(ZipFile zipFile, ZipEntry fb2Entry) {
        try (InputStream is = zipFile.getInputStream(fb2Entry)) {
            String content = new String(is.readAllBytes(), "UTF-8");
            log.debug("FB2 прочитано, довжина: {} символів", content.length());

            String coverImageId = extractCoverImageIdFromFb2(content);
            if (coverImageId == null) {
                log.warn("Не знайдено <coverpage>");
                return searchAnyImageInArchive(zipFile);
            }

            log.info("coverImageId з FB2: {}", coverImageId);

            // Спроба 1: точне співпадіння
            ZipEntry binary = zipFile.getEntry(coverImageId);
            if (binary != null) {
                log.info("Binary знайдено за точним id");
                return loadImageFromEntry(zipFile, binary);
            }

            // Спроба 2: варіанти з розширенням
            String base = coverImageId.replaceAll("\\.(jpg|jpeg|png|gif|bmp)$", "");
            String[] exts = {".jpg", ".jpeg", ".png", ".gif", ".bmp"};
            for (String ext : exts) {
                ZipEntry tryEntry = zipFile.getEntry(base + ext);
                if (tryEntry != null) {
                    log.info("Binary знайдено за варіантом: {}", tryEntry.getName());
                    return loadImageFromEntry(zipFile, tryEntry);
                }
            }

            // Спроба 3: шукаємо будь-яке зображення в архіві
            log.debug("Шукаємо будь-яку обкладинку в архіві...");
            return searchAnyImageInArchive(zipFile);

        } catch (Exception e) {
            log.error("Помилка обробки FB2", e);
            return null;
        }
    }

    private Image searchAnyImageInArchive(ZipFile zipFile) {
        try {
            for (ZipEntry entry : zipFile.stream().toList()) {
                if (entry.isDirectory()) continue;
                String name = entry.getName().toLowerCase();
                if (name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                        name.endsWith(".png") || name.endsWith(".gif")) {

                    if (name.contains("cover") || name.contains("dozor") ||
                            name.contains("preview") || name.contains("folder")) {
                        log.info("Знайдено ймовірну обкладинку: {}", entry.getName());
                        return loadImageFromEntry(zipFile, entry);
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Помилка при пошуку будь-якого зображення", e);
        }
        return null;
    }

    private Image loadImageFromEntry(ZipFile zipFile, ZipEntry entry) throws Exception {
        try (InputStream is = zipFile.getInputStream(entry)) {
            Image image = new Image(is, DEFAULT_COVER_WIDTH, DEFAULT_COVER_HEIGHT, true, true);
            if (!image.isError()) {
                log.info("✅ Обкладинку успішно завантажено з {}", entry.getName());
                return image;
            }
        }
        return null;
    }



    private ZipEntry findFb2Entry(ZipFile zipFile) {
        return zipFile.stream()
                .filter(entry -> entry.getName().toLowerCase().endsWith(".fb2"))
                .findFirst()
                .orElse(null);
    }

    private Image extractCoverFromFb2File(Path fb2Path) throws Exception {
        String content = new String(Files.readAllBytes(fb2Path));
        String coverImageId = extractCoverImageIdFromFb2(content);
        if (coverImageId == null) return null;

        String binaryData = extractBinaryData(content, coverImageId);
        if (binaryData != null) {
            byte[] imageBytes = Base64.getDecoder().decode(binaryData);
            try (ByteArrayInputStream bis = new ByteArrayInputStream(imageBytes)) {
                Image image = new Image(bis, DEFAULT_COVER_WIDTH, DEFAULT_COVER_HEIGHT, true, true);
                if (!image.isError()) {
                    return image;
                }
            }
        }
        return null;
    }

    private String extractCoverImageIdFromFb2(String fb2Content) {
        // Більш стійкий пошук
        String lower = fb2Content.toLowerCase();

        int coverStart = lower.indexOf("<coverpage>");
        if (coverStart == -1) {
            coverStart = lower.indexOf("<cover-page>");
            if (coverStart == -1) return null;
        }

        int coverEnd = lower.indexOf("</coverpage>", coverStart);
        if (coverEnd == -1) coverEnd = lower.indexOf("</cover-page>", coverStart);
        if (coverEnd == -1) return null;

        String coverPart = fb2Content.substring(coverStart, coverEnd);

        // Різні можливі варіанти
        String[] patterns = {"xlink:href=\"#", "href=\"#"};
        for (String pattern : patterns) {
            int hrefStart = coverPart.indexOf(pattern);
            if (hrefStart != -1) {
                hrefStart += pattern.length();
                int hrefEnd = coverPart.indexOf("\"", hrefStart);
                if (hrefEnd != -1) {
                    String id = coverPart.substring(hrefStart, hrefEnd).trim();
                    log.debug("Знайдено cover id: {}", id);
                    return id;
                }
            }
        }
        return null;
    }

    private String extractBinaryData(String fb2Content, String id) {
        String search = "id=\"" + id + "\"";
        int binaryStart = fb2Content.indexOf("<binary " + search);
        if (binaryStart == -1) return null;
        int tagEnd = fb2Content.indexOf(">", binaryStart);
        if (tagEnd == -1) return null;
        int binaryEnd = fb2Content.indexOf("</binary>", tagEnd);
        if (binaryEnd == -1) return null;

        String binaryContent = fb2Content.substring(tagEnd + 1, binaryEnd).trim();
        return binaryContent.replaceAll("\\s+", "");
    }

    private Image extractFromSidecar(Path filePath) {
        Path parent = filePath.getParent();
        if (parent == null) return null;

        String[] sidecarNames = {"cover.jpg", "cover.jpeg", "cover.png", "cover.gif", "cover.bmp"};
        for (String name : sidecarNames) {
            Path sidecar = parent.resolve(name);
            if (Files.exists(sidecar)) {
                try (InputStream is = Files.newInputStream(sidecar)) {
                    Image image = new Image(is, DEFAULT_COVER_WIDTH, DEFAULT_COVER_HEIGHT, true, true);
                    if (!image.isError()) {
                        return image;
                    }
                } catch (Exception e) {
                    log.warn("Не вдалося завантажити {}: {}", name, e.getMessage());
                }
            }
        }
        return null;
    }
}