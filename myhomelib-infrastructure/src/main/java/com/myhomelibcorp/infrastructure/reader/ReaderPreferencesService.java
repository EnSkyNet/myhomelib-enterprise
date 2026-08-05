package com.myhomelibcorp.infrastructure.reader;

import com.myhomelibcorp.application.port.out.reader.ReaderPreferencesPort;
import com.myhomelibcorp.domain.model.reader.ReaderPreferences;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.prefs.Preferences;

@Service
@Slf4j
public class ReaderPreferencesService implements ReaderPreferencesPort {

    private static final String PREFS_NODE = "myhomelib/reader";
    private static final String PREF_KEY = "preferences";
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public ReaderPreferences loadPreferences() {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            String json = prefs.get(PREF_KEY, null);
            if (json != null) {
                return objectMapper.readValue(json, ReaderPreferences.class);
            }
        } catch (Exception e) {
            log.warn("Не вдалося завантажити налаштування Reader, використовуємо стандартні", e);
        }
        return ReaderPreferences.builder().build();
    }

    @Override
    public void savePreferences(ReaderPreferences preferences) {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            String json = objectMapper.writeValueAsString(preferences);
            prefs.put(PREF_KEY, json);
        } catch (Exception e) {
            log.error("Не вдалося зберегти налаштування Reader", e);
        }
    }

    @Override
    public void resetPreferences() {
        try {
            Preferences prefs = Preferences.userRoot().node(PREFS_NODE);
            prefs.remove(PREF_KEY);
        } catch (Exception e) {
            log.error("Не вдалося скинути налаштування Reader", e);
        }
    }
}