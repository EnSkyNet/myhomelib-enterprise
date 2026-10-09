package com.myhomelibcorp.infrastructure.reader;

import tools.jackson.databind.ObjectMapper;
import com.myhomelibcorp.application.port.out.reader.ReaderPreferencesPort;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import com.myhomelibcorp.shared.util.AppPaths;
import com.myhomelibcorp.shared.util.BoundedIoSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.LinkOption;
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
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) {
                ReaderPreferences loaded = readExistingPreferences();
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
        Path workspace = null;
        try {
            // Never replace an existing preferences file that we cannot safely parse. Otherwise a
            // transient/corrupt source would be silently destroyed by freshly serialized defaults.
            if (Files.exists(file, LinkOption.NOFOLLOW_LINKS)) readExistingPreferences();
            String expectedTargetHash = SafeFilePublication.snapshotExistingTarget(file, "reader preferences");
            workspace = SafeFilePublication.createPrivateWorkspace(
                    file.getParent(), ".reader-preferences-work-", "reader preferences workspace");
            Path tmp = workspace.resolve("reader-preferences.json");
            try (var out = SafeFilePublication.openFreshOutputStream(tmp, "staged reader preferences")) {
                objectMapper.writerWithDefaultPrettyPrinter().writeValue(out, preferences);
            }
            SafeFilePublication.publishReplacingIfUnchanged(
                    tmp, file, expectedTargetHash, "reader preferences publication");
            log.debug("Saved reader preferences to {}", file);
        } catch (Exception e) {
            log.error("Не вдалося зберегти налаштування Reader", e);
            throw new IllegalStateException("Не вдалося зберегти налаштування Reader", e);
        } finally {
            if (workspace != null) {
                try { SafeFilePublication.cleanupPrivateWorkspace(workspace); }
                catch (Exception ignored) { }
            }
        }
    }

    private ReaderPreferences readExistingPreferences() throws Exception {
        Path safe = SafeFilePublication.requireSafeRegularFile(file, "reader preferences");
        try (var in = Files.newInputStream(safe, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = BoundedIoSupport.readFully(in, (int) ReaderPreferencesJsonCodec.MAX_JSON_BYTES);
            return codec.decode(objectMapper.readTree(bytes));
        }
    }

    @Override
    public void resetPreferences() {
        try {
            String expectedTargetHash = SafeFilePublication.snapshotExistingTarget(file, "reader preferences reset");
            if (expectedTargetHash != null) {
                SafeFilePublication.removeIfOwned(file, expectedTargetHash, "reader preferences reset");
            }
            log.info("Reader preferences reset");
        } catch (Exception e) {
            log.error("Не вдалося скинути налаштування Reader", e);
            throw new IllegalStateException("Не вдалося скинути налаштування Reader", e);
        }
    }
}
