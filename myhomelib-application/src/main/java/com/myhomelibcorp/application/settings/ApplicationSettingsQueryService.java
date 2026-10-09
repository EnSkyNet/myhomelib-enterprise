package com.myhomelibcorp.application.settings;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.springframework.stereotype.Service;

import java.util.Objects;

/**
 * Read-only application boundary for presentation code that needs runtime settings.
 *
 * <p>UI code must not depend directly on persistence/output ports. Keeping reads behind
 * this application service also gives one place for normalization and future policy.</p>
 */
@Service
public final class ApplicationSettingsQueryService {
    private final ApplicationSettingsPort settings;

    public ApplicationSettingsQueryService(ApplicationSettingsPort settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
    }

    public String get(String key, String defaultValue) {
        String value = settings.get(key, defaultValue);
        return value == null ? defaultValue : value;
    }

    public boolean getBoolean(String key, boolean defaultValue) {
        return settings.getBoolean(key, defaultValue);
    }
}
