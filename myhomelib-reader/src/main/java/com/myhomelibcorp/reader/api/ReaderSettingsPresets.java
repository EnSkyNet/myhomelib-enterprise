package com.myhomelibcorp.reader.api;

import java.util.List;

public final class ReaderSettingsPresets {
    private ReaderSettingsPresets() {}

    public static List<ReaderSettingsPreset> builtIns() {
        ReaderSettings d = ReaderSettings.defaultSettings();
        return List.of(
                new ReaderSettingsPreset("default", "Стандартний", d),
                new ReaderSettingsPreset("comfortable", "Комфорт", new ReaderSettings(
                        "sepia", "Georgia", 20, 1.72, 1.7, 1.4, "justify",
                        42, 42, 28, 28, true, false, false, 3, true, "",
                        true, true, true, true,
                        "previous-page", "toggle-toolbar", "next-page")),
                new ReaderSettingsPreset("compact", "Компактний", new ReaderSettings(
                        "light", "Georgia", 16, 1.42, 0.9, 1.1, "justify",
                        20, 20, 14, 14, true, false, false, 3, true, "",
                        true, true, true, true,
                        "previous-page", "toggle-toolbar", "next-page")),
                new ReaderSettingsPreset("night", "Нічний", new ReaderSettings(
                        "amoled", "Georgia", 19, 1.65, 1.5, 1.3, "justify",
                        34, 34, 22, 22, true, false, false, 2, true, "",
                        true, true, true, true,
                        "previous-page", "toggle-toolbar", "next-page"))
        );
    }
}
