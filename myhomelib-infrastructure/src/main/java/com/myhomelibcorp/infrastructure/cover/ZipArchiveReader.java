package com.myhomelibcorp.infrastructure.cover;

import com.myhomelibcorp.application.port.out.cover.ArchiveReader;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Optional;
import java.util.function.Predicate;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Component
@Slf4j
public class ZipArchiveReader implements ArchiveReader {

    private static final Charset[] CHARSETS = {
            Charset.forName("Windows-1251"),
            Charset.forName("CP866"),
            Charset.forName("UTF-8"),
            Charset.forName("IBM-866"),
            Charset.forName("KOI8-R")
    };

    @Override
    public boolean isArchive(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return name.endsWith(".zip") || name.endsWith(".fb2zip") || name.endsWith(".fbd");
    }

    @Override
    public List<String> listEntries(Path archivePath) {
        for (Charset charset : CHARSETS) {
            try (ZipFile zip = new ZipFile(archivePath.toFile(), charset)) {
                List<String> entries = new ArrayList<>();
                Enumeration<? extends ZipEntry> enumeration = zip.entries();
                while (enumeration.hasMoreElements()) {
                    ZipEntry entry = enumeration.nextElement();
                    if (!entry.isDirectory()) {
                        // Повертаємо оригінальне ім'я без перекодування
                        entries.add(entry.getName());
                    }
                }
                if (!entries.isEmpty()) {
                    log.debug("Архів прочитано з кодуванням {}, знайдено {} записів", charset, entries.size());
                }
                return entries;
            } catch (Exception e) {
                log.debug("Не вдалося прочитати архів з кодуванням {}: {}", charset, e.getMessage());
            }
        }
        log.warn("Не вдалося прочитати архів жодним з підтримуваних кодувань: {}", archivePath);
        return List.of();
    }

    @Override
    public Optional<InputStream> readEntry(Path archivePath, String entryName) {
        for (Charset charset : CHARSETS) {
            try (ZipFile zip = new ZipFile(archivePath.toFile(), charset)) {
                // Шукаємо за оригінальним ім'ям
                ZipEntry entry = zip.getEntry(entryName);
                if (entry != null) {
                    log.debug("Запис знайдено з кодуванням {}", charset);
                    byte[] data = zip.getInputStream(entry).readAllBytes();
                    return Optional.of(new ByteArrayInputStream(data));
                }
            } catch (Exception e) {
                log.debug("Не вдалося прочитати запис з кодуванням {}: {}", charset, e.getMessage());
            }
        }
        log.warn("Не знайдено запис {} в архіві {}", entryName, archivePath);
        return Optional.empty();
    }

    @Override
    public Optional<InputStream> findFirstEntry(Path archivePath, Predicate<String> filter) {
        for (Charset charset : CHARSETS) {
            try (ZipFile zip = new ZipFile(archivePath.toFile(), charset)) {
                Enumeration<? extends ZipEntry> enumeration = zip.entries();
                while (enumeration.hasMoreElements()) {
                    ZipEntry entry = enumeration.nextElement();
                    if (!entry.isDirectory()) {
                        // Для фільтра використовуємо оригінальне ім'я
                        if (filter.test(entry.getName())) {
                            log.debug("Знайдено запис з кодуванням {}", charset);
                            byte[] data = zip.getInputStream(entry).readAllBytes();
                            return Optional.of(new ByteArrayInputStream(data));
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Не вдалося прочитати архів з кодуванням {}: {}", charset, e.getMessage());
            }
        }
        log.warn("Не знайдено жодного запису, що відповідає фільтру, в архіві {}", archivePath);
        return Optional.empty();
    }
}