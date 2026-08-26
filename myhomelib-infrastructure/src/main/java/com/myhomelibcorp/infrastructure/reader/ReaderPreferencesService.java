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
                var node = objectMapper.readTree(file.toFile());
                ReaderPreferences loaded = objectMapper.treeToValue(node, ReaderPreferences.class);
                var builder = loaded.toBuilder();
                // Backward compatibility: Stage-18 and older JSON files do not
                // contain the Stage-19 status/tap-zone fields. Jackson's forced
                // no-args constructor would otherwise turn missing booleans into false.
                if (!node.has("showStatusBar")) builder.showStatusBar(true);
                if (!node.has("showStatusProgress")) builder.showStatusProgress(true);
                if (!node.has("showStatusChapter")) builder.showStatusChapter(true);
                if (!node.has("showStatusPage")) builder.showStatusPage(true);
                if (!node.has("tapLeftAction") || node.path("tapLeftAction").asText("").isBlank()) builder.tapLeftAction("previous-page");
                if (!node.has("tapCenterAction") || node.path("tapCenterAction").asText("").isBlank()) builder.tapCenterAction("toggle-toolbar");
                if (!node.has("tapRightAction") || node.path("tapRightAction").asText("").isBlank()) builder.tapRightAction("next-page");
                loaded = builder.build();
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
