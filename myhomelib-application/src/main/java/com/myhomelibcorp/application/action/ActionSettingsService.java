package com.myhomelibcorp.application.action;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Application-level boundary for persisted ActionRegistry customization. */
@Component
@RequiredArgsConstructor
public class ActionSettingsService {
    private static final String PREFIX = "actions.";
    private final ApplicationSettingsPort settings;

    public ActionPreference load(String commandId, String defaultShortcut, boolean defaultVisible) {
        String base = key(commandId);
        return new ActionPreference(
                settings.get(base + ".shortcut", defaultShortcut == null ? "" : defaultShortcut),
                settings.getBoolean(base + ".visible", defaultVisible));
    }

    public void save(String commandId, ActionPreference preference) {
        if (preference == null) return;
        String base = key(commandId);
        settings.put(base + ".shortcut", preference.shortcut());
        settings.putBoolean(base + ".visible", preference.visible());
    }

    public void reset(String commandId) {
        String base = key(commandId);
        settings.remove(base + ".shortcut");
        settings.remove(base + ".visible");
    }

    private static String key(String commandId) {
        if (commandId == null || !commandId.matches("[A-Za-z0-9._-]+")) {
            throw new IllegalArgumentException("Invalid command id: " + commandId);
        }
        return PREFIX + commandId;
    }
}
