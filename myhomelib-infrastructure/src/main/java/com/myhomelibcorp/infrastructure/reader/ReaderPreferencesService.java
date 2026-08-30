package com.myhomelibcorp.infrastructure.reader;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.port.out.reader.ReaderPreferencesPort;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.AtomicFileSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;

@Service
@RequiredArgsConstructor
@Slf4j
public class ReaderPreferencesService implements ReaderPreferencesPort {

    private final ObjectMapper objectMapper;
    private final ReaderPreferencesJsonCodec codec;
    private final Path file = AppPaths.configDir().resolve("reader-preferences.json");

    @Override
    public ReaderPreferences loadPreferences() {
        try {
            if (Files.isRegularFile(file)) {
                long size = Files.size(file);
                if (size > ReaderPreferencesJsonCodec.MAX_JSON_BYTES) {
                    throw new IllegalArgumentException("Reader preferences file exceeds " + ReaderPreferencesJsonCodec.MAX_JSON_BYTES + " bytes");
                }
                ReaderPreferences loaded = codec.decode(objectMapper.readTree(file.toFile()));
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
            AtomicFileSupport.moveReplacing(tmp, file);
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
