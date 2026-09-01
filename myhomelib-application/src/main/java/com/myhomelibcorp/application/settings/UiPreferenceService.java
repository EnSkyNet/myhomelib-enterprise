package com.myhomelibcorp.application.settings;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * JavaFX-neutral facade for persisted UI preferences.
 * UI code depends on this application service instead of reaching through an output port directly.
 */
@Component
@RequiredArgsConstructor
public class UiPreferenceService {
    private final ApplicationSettingsPort settings;

    public String get(String key, String fallback) {
        return settings.get(key, fallback);
    }

    public void put(String key, String value) {
        settings.put(key, value == null ? "" : value);
    }
}
