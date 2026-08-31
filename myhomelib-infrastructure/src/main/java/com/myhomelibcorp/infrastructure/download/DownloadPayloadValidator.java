package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.ArchiveReader;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.Charset;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

@Slf4j
@Component
public class DownloadPayloadValidator {
    private static final int PREFIX_LIMIT = 8192;
    private static final Charset[] ZIP_CHARSETS = {
            StandardCharsets.UTF_8,
            Charset.forName("CP866"),
            Charset.forName("Windows-1251"),
            Charset.forName("KOI8-R")
    };

    private final ArchiveReader archiveReader;
    private final ApplicationSettingsPort settings;

    public DownloadPayloadValidator(ArchiveReader archiveReader) { this(archiveReader, null); }

    @Autowired
    public DownloadPayloadValidator(ArchiveReader archiveReader, ApplicationSettingsPort settings) {
        this.archiveReader = archiveReader;
        this.settings = settings;
    }

    public void validate(Path payload, Path intendedTarget, BookDto book, boolean archived) throws IOException {
        if (payload == null || !Files.isRegularFile(payload) || Files.size(payload) <= 0) {
            throw new IOException("Сервер повернув порожній payload");
        }
        rejectHtmlOrTextError(payload);
        if (archived) {
            String foundEntry = validateArchive(payload, intendedTarget, book);
            log.info("Archive validated. Found entry: {}", foundEntry);
        } else if (looksLikeFb2(intendedTarget, book)) {
            validateFb2(payload);
        }
    }

    /**
     * Валідує архів та повертає ім'я знайденого entry (якщо воно відрізняється від запитуваного).
     * @return ім'я entry, яке було знайдено (може відрізнятися від запитуваного)
     */
    private String validateArchive(Path payload, Path target, BookDto book) throws IOException {
        Path alias = payload.resolveSibling(".validate-" + target.getFileName());
        boolean linked = false;
        String foundEntry = null;

        try {
            Files.deleteIfExists(alias);
            try { Files.createLink(alias, payload); linked = true; }
            catch (Exception e) { Files.copy(payload, alias); }

            // Отримуємо список записів без try-with-resources
            List<String> entries = archiveReader.listEntries(alias);
            if (entries.isEmpty()) throw new IOException("Завантажений архів пошкоджений або порожній");
            if (highReliabilityArchiveValidation()) validateUniqueEntryNames(entries);

            String requested = normalize(book.getArchiveEntry());
            log.debug("validateArchive: requested entry='{}', entries count={}", requested, entries.size());

            // 1. Спроба знайти точний збіг
            boolean found = false;
            if (!requested.isBlank()) {
                found = entries.stream().map(DownloadPayloadValidator::normalize)
                        .anyMatch(e -> e.equalsIgnoreCase(requested));
                if (found) {
                    log.debug("Found exact match: {}", requested);
                    foundEntry = requested;
                }
            }

            // 2. Якщо не знайдено - шукаємо будь-який FB2-файл
            if (!found) {
                String fb2Entry = entries.stream()
                        .filter(e -> e.toLowerCase(Locale.ROOT).endsWith(".fb2"))
                        .findFirst()
                        .orElse(null);
                if (fb2Entry != null) {
                    log.info("Archive does not contain '{}', but contains FB2 entry: '{}'. Using it instead.", requested, fb2Entry);
                    found = true;
                    foundEntry = fb2Entry;
                }
            }

            // 3. Якщо все ще не знайдено - помилка
            if (!found) {
                String sample = entries.stream().limit(5).collect(java.util.stream.Collectors.joining(", "));
                throw new IOException("Завантажений архів не містить запис: " + requested + ". Доступні: " + sample);
            }

            // ===== ВИКОРИСТОВУЄМО ЗНАЙДЕНИЙ ENTRY =====
            final String entryToRead = foundEntry;
            log.info("Reading archive entry: {}", entryToRead);
            try (InputStream in = archiveReader.readEntry(alias, entryToRead)
                    .orElseThrow(() -> new IOException("Не вдалося прочитати запис архіву: " + entryToRead))) {
                if (in.read() < 0) throw new IOException("Запис архіву порожній: " + entryToRead);
            }

            if (highReliabilityArchiveValidation() && isZipFamily(target)) {
                validateZipIntegrity(alias, entryToRead);
            }

            return foundEntry;

        } finally {
            Files.deleteIfExists(alias);
        }
    }

    private boolean highReliabilityArchiveValidation() {
        return settings != null && settings.getBoolean("online.archive.highReliabilityValidation", false);
    }

    private void validateUniqueEntryNames(List<String> entries) throws IOException {
        Set<String> seen = new HashSet<>();
        for (String entry : entries) {
            String key = normalize(entry).toLowerCase(Locale.ROOT);
            if (!key.isBlank() && !seen.add(key)) {
                throw new IOException("Архів містить дубльоване ім'я запису: " + entry);
            }
        }
    }

    /**
     * Full ZIP-family integrity scan used only in the opt-in high-reliability mode.
     * Reading every entry verifies the declared size and CRC after a newly downloaded
     * archive; this is deliberately not a startup scan and is not used for unchanged files.
     */
    private void validateZipIntegrity(Path archive, String requested) throws IOException {
        IOException last = null;
        for (Charset charset : ZIP_CHARSETS) {
            try (ZipFile zip = new ZipFile(archive.toFile(), charset)) {
                Set<String> seen = new HashSet<>();
                var entries = zip.entries();
                boolean requestedSeen = requested == null || requested.isBlank();
                while (entries.hasMoreElements()) {
                    ZipEntry entry = entries.nextElement();
                    if (entry.isDirectory()) continue;
                    String normalized = normalize(entry.getName());
                    String key = normalized.toLowerCase(Locale.ROOT);
                    if (!seen.add(key)) throw new IOException("ZIP містить дубльоване ім'я запису: " + entry.getName());
                    if (!requestedSeen && normalized.equalsIgnoreCase(requested)) requestedSeen = true;

                    CRC32 crc = new CRC32();
                    long actualSize = 0L;
                    byte[] prefix = new byte[PREFIX_LIMIT];
                    int prefixSize = 0;
                    try (InputStream in = zip.getInputStream(entry)) {
                        byte[] buffer = new byte[64 * 1024];
                        for (int n; (n = in.read(buffer)) >= 0;) {
                            if (n == 0) continue;
                            crc.update(buffer, 0, n);
                            if (prefixSize < prefix.length) {
                                int copy = Math.min(n, prefix.length - prefixSize);
                                System.arraycopy(buffer, 0, prefix, prefixSize, copy);
                                prefixSize += copy;
                            }
                            actualSize += n;
                        }
                    }
                    if (entry.getSize() >= 0 && actualSize != entry.getSize()) {
                        throw new IOException("ZIP entry size mismatch: " + entry.getName());
                    }
                    if (entry.getCrc() >= 0 && crc.getValue() != entry.getCrc()) {
                        throw new IOException("ZIP entry CRC mismatch: " + entry.getName());
                    }
                    if (actualSize == 0 && normalized.toLowerCase(Locale.ROOT).endsWith(".fb2")) {
                        throw new IOException("Порожній FB2 запис у ZIP: " + entry.getName());
                    }
                    if (normalized.toLowerCase(Locale.ROOT).endsWith(".fb2") && prefixSize > 0) {
                        String text = new String(prefix, 0, prefixSize, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                        if (!text.contains("<fictionbook") && !text.contains(":fictionbook")) {
                            throw new IOException("Некоректний FB2 запис у ZIP: " + entry.getName());
                        }
                    }
                }
                if (!requestedSeen) throw new IOException("ZIP не містить очікуваного запису: " + requested);
                return;
            } catch (IOException e) {
                last = e;
            }
        }
        throw last == null ? new IOException("Не вдалося виконати повну ZIP integrity validation") : last;
    }

    private boolean isZipFamily(Path target) {
        if (target == null || target.getFileName() == null) return false;
        String name = target.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".zip") || name.endsWith(".fb2zip") || name.endsWith(".fb2.zip")
                || name.endsWith(".cbz") || name.endsWith(".jar");
    }

    private void rejectHtmlOrTextError(Path file) throws IOException {
        byte[] prefix;
        try (InputStream in = Files.newInputStream(file)) { prefix = in.readNBytes(PREFIX_LIMIT); }
        String s = new String(prefix, StandardCharsets.UTF_8).stripLeading().toLowerCase(Locale.ROOT);
        if (s.startsWith("<!doctype html") || s.startsWith("<html") || s.contains("<form") && s.contains("password")) {
            throw new IOException("Сервер повернув HTML/login page замість книги");
        }
        if (s.startsWith("error:") || s.startsWith("not found") || s.startsWith("access denied") || s.startsWith("forbidden")) {
            throw new IOException("Сервер повернув текст помилки замість книги");
        }
    }

    private void validateFb2(Path file) throws IOException {
        byte[] prefix;
        try (InputStream in = Files.newInputStream(file)) { prefix = in.readNBytes(PREFIX_LIMIT); }
        String s = new String(prefix, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        if (!s.contains("<fictionbook") && !s.contains(":fictionbook")) {
            throw new IOException("Некоректний FB2 payload: відсутній FictionBook root element");
        }
    }

    private boolean looksLikeFb2(Path target, BookDto book) {
        String name = target == null ? "" : target.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".fb2")) return true;
        String file = book == null || book.getFileName() == null ? "" : book.getFileName().toLowerCase(Locale.ROOT);
        return file.endsWith(".fb2");
    }

    private static String normalize(String value) {
        if (value == null) return "";
        String v = value.replace('\\', '/').trim();
        while (v.startsWith("/")) v = v.substring(1);
        return v;
    }
}