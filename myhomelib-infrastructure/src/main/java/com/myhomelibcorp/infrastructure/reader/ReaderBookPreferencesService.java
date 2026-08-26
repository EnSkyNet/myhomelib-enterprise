package com.myhomelibcorp.infrastructure.reader;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.port.out.reader.ReaderBookPreferencesPort;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

@Service
@Slf4j
public class ReaderBookPreferencesService implements ReaderBookPreferencesPort {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path file = AppPaths.configDir().resolve("reader-book-preferences.json");
    private final Object lock = new Object();

    @Override
    public Optional<ReaderPreferences> load(String bookId) {
        if (bookId == null || bookId.isBlank()) return Optional.empty();
        synchronized (lock) {
            return Optional.ofNullable(readAll().get(bookId));
        }
    }

    @Override
    public void save(String bookId, ReaderPreferences preferences) {
        if (bookId == null || bookId.isBlank() || preferences == null) return;
        synchronized (lock) {
            Map<String, ReaderPreferences> all = readAll();
            all.put(bookId, preferences);
            writeAll(all);
        }
    }

    @Override
    public void delete(String bookId) {
        if (bookId == null || bookId.isBlank()) return;
        synchronized (lock) {
            Map<String, ReaderPreferences> all = readAll();
            if (all.remove(bookId) != null) writeAll(all);
        }
    }

    private Map<String, ReaderPreferences> readAll() {
        if (!Files.isRegularFile(file)) return new LinkedHashMap<>();
        try {
            Map<String, ReaderPreferences> loaded = objectMapper.readValue(
                    file.toFile(), new TypeReference<Map<String, ReaderPreferences>>() {});
            return loaded == null ? new LinkedHashMap<>() : new LinkedHashMap<>(loaded);
        } catch (Exception e) {
            log.warn("Не вдалося прочитати per-book Reader settings; використовуємо порожній набір", e);
            return new LinkedHashMap<>();
        }
    }

    private void writeAll(Map<String, ReaderPreferences> all) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), all);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception unsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception e) {
            log.error("Не вдалося зберегти per-book Reader settings", e);
        }
    }
}
