package com.myhomelibcorp.application.session;

import org.junit.jupiter.api.Test;

import java.util.prefs.Preferences;

import static org.assertj.core.api.Assertions.assertThat;

class SessionServiceWindowStateTest {
    @Test
    void corruptedNonFiniteWindowPreferencesFallBackToSafeDefaults() {
        Preferences prefs = Preferences.userNodeForPackage(SessionService.class);
        String oldWidth = prefs.get("windowWidth", null);
        String oldHeight = prefs.get("windowHeight", null);
        try {
            prefs.put("windowWidth", "NaN");
            prefs.put("windowHeight", "Infinity");
            SessionService service = new SessionService(null, null, null, null);

            assertThat(service.getWindowState()).containsExactly(1200.0, 800.0);
        } finally {
            restore(prefs, "windowWidth", oldWidth);
            restore(prefs, "windowHeight", oldHeight);
        }
    }

    @Test
    void nonPositiveWindowPreferencesFallBackToSafeDefaults() {
        Preferences prefs = Preferences.userNodeForPackage(SessionService.class);
        String oldWidth = prefs.get("windowWidth", null);
        String oldHeight = prefs.get("windowHeight", null);
        try {
            prefs.putDouble("windowWidth", -1.0);
            prefs.putDouble("windowHeight", 0.0);
            SessionService service = new SessionService(null, null, null, null);

            assertThat(service.getWindowState()).containsExactly(1200.0, 800.0);
        } finally {
            restore(prefs, "windowWidth", oldWidth);
            restore(prefs, "windowHeight", oldHeight);
        }
    }

    private static void restore(Preferences prefs, String key, String value) {
        if (value == null) prefs.remove(key);
        else prefs.put(key, value);
    }
}
