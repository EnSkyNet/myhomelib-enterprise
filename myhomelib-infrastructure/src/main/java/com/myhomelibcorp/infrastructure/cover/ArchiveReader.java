package com.myhomelibcorp.infrastructure.cover;

import javafx.scene.image.Image;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
@RequiredArgsConstructor
@Slf4j
public class ArchiveReader {

    private final Fb2CoverParser fb2Parser;
    private final ExternalImageFinder externalImageFinder;
    private final ImageLoader imageLoader;

    private static final Charset[] CHARSETS = {
            Charset.forName("CP866"),
            Charset.forName("Windows-1251"),
            StandardCharsets.UTF_8
    };

    public Image extractCover(Path archivePath, String archiveEntry) {
        if (archivePath == null || !archivePath.toFile().exists()) {
            return null;
        }

        for (Charset cs : CHARSETS) {
            try (ZipFile zip = new ZipFile(archivePath.toFile(), cs)) {
                Enumeration<? extends ZipEntry> entries = zip.entries();

                // 1) Шукаємо FB2 файл
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName().toLowerCase();

                    if (name.endsWith(".fb2")) {
                        try (InputStream is = zip.getInputStream(entry)) {
                            Image img = fb2Parser.parse(is);
                            if (img != null) {
                                log.debug("Cover from FB2 in archive: {}", entry.getName());
                                return img;
                            }
                        }
                    }
                }

                // 2) Шукаємо окремі зображення
                entries = zip.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    String name = entry.getName().toLowerCase();

                    if (name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                            name.endsWith(".png") || name.endsWith(".gif")) {
                        try (InputStream is = zip.getInputStream(entry)) {
                            Image img = imageLoader.loadFromStream(is);
                            if (img != null) {
                                log.debug("Cover as image in archive: {}", entry.getName());
                                return img;
                            }
                        }
                    }
                }

                // 3) Якщо задано archiveEntry – пробуємо саме його
                if (archiveEntry != null && !archiveEntry.isBlank()) {
                    ZipEntry specific = zip.getEntry(archiveEntry);
                    if (specific != null && specific.getName().toLowerCase().endsWith(".fb2")) {
                        try (InputStream is = zip.getInputStream(specific)) {
                            Image img = fb2Parser.parse(is);
                            if (img != null) {
                                log.debug("Cover from specific FB2 entry: {}", archiveEntry);
                                return img;
                            }
                        }
                    }
                }

            } catch (Exception e) {
                log.trace("Failed to open ZIP with charset {}: {}", cs, e.getMessage());
            }
        }

        return null;
    }
}