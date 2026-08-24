package com.myhomelibcorp.infrastructure.reader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.port.out.reader.ReaderPreferencesPort;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

@Service
@Slf4j
public class ReaderPreferencesService implements ReaderPreferencesPort {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Path file = AppPaths.configDir().resolve("reader-preferences.json");

    @Override
    public ReaderPreferences loadPreferences() {
        try {
            if (Files.isRegularFile(file)) {
                ReaderPreferences loaded = objectMapper.readValue(file.toFile(), ReaderPreferences.class);
                log.debug("Loaded reader preferences from {}", file);
                return loaded;
            }
        } catch (Exception e) {
            log.warn("Не вдалося завантажити налаштування Reader, використовуємо стандартні", e);
        }
        return ReaderPreferences.builder().build();
    }

    @Override
    public void savePreferences(ReaderPreferences preferences) {
        try {
            Files.createDirectories(file.getParent());
            Path tmp = file.resolveSibling(file.getFileName() + ".tmp");
            objectMapper.writerWithDefaultPrettyPrinter().writeValue(tmp.toFile(), preferences);
            try {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Exception unsupported) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            log.debug("Saved reader preferences to {}", file);
        } catch (Exception e) {
            log.error("Не вдалося зберегти налаштування Reader", e);
        }
    }

    @Override
    public void resetPreferences() {
        try {
            Files.deleteIfExists(file);
            log.info("Reader preferences reset");
        } catch (Exception e) {
            log.error("Не вдалося скинути налаштування Reader", e);
        }
    }
}
