package com.myhomelibcorp.ui.service;

import org.junit.jupiter.api.Test;

import java.util.Locale;
import java.util.ResourceBundle;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

/** Ensures JavaFX's own control strings do not fall back to English for shipped Cyrillic locales. */
class JavaFxControlResourcesTest {
    private static final String BASE = "com/sun/javafx/scene/control/skin/resources/controls";

    @Test
    void ukrainianBundleCoversEveryJavaFxBaseControlKey() {
        assertComplete("uk", "Копіювати", "Немає даних у таблиці", "Скасувати");
    }

    @Test
    void bulgarianBundleCoversEveryJavaFxBaseControlKey() {
        assertComplete("bg", "Копирай", "Няма данни в таблицата", "Отказ");
    }

    @Test
    void javaFxControlResourcesFollowRuntimeLanguageSwitchForEveryLocalizedKey() throws Exception {
        Locale before = Locale.getDefault();
        try {
            Class<?> resources = Class.forName("com.sun.javafx.scene.control.skin.resources.ControlResources");
            Method getString = resources.getMethod("getString", String.class);
            for (String language : java.util.List.of("uk", "bg")) {
                Locale.setDefault(Locale.forLanguageTag(language));
                ResourceBundle.clearCache();
                ResourceBundle expected = ResourceBundle.getBundle(BASE, Locale.forLanguageTag(language),
                        JavaFxControlResourcesTest.class.getClassLoader());
                for (String key : expected.keySet()) {
                    assertThat((String) getString.invoke(null, key))
                            .as("JavaFX control resource %s for %s", key, language)
                            .isEqualTo(expected.getString(key));
                }
            }
        } finally {
            Locale.setDefault(before);
            ResourceBundle.clearCache();
        }
    }

    @Test
    void javaFxControlResourcesActuallyResolveApplicationUkrainianBundle() throws Exception {
        Locale before = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("uk"));
            ResourceBundle.clearCache();
            Class<?> resources = Class.forName("com.sun.javafx.scene.control.skin.resources.ControlResources");
            Method getString = resources.getMethod("getString", String.class);
            assertThat((String) getString.invoke(null, "TextInputControl.menu.Copy")).isEqualTo("Копіювати");
            assertThat((String) getString.invoke(null, "TableView.noContent")).isEqualTo("Немає даних у таблиці");
            assertThat((String) getString.invoke(null, "Dialog.cancel.button")).isEqualTo("Скасувати");
        } finally {
            Locale.setDefault(before);
            ResourceBundle.clearCache();
        }
    }

    private static void assertComplete(String language, String copy, String emptyTable, String cancel) {
        ClassLoader loader = JavaFxControlResourcesTest.class.getClassLoader();
        ResourceBundle base = ResourceBundle.getBundle(BASE, Locale.ROOT, loader);
        ResourceBundle localized = ResourceBundle.getBundle(BASE, Locale.forLanguageTag(language), loader);

        assertThat(localized.getLocale().getLanguage()).isEqualTo(language);
        assertThat(localized.keySet()).containsAll(base.keySet());
        assertThat(localized.getString("TextInputControl.menu.Copy")).isEqualTo(copy);
        assertThat(localized.getString("TableView.noContent")).isEqualTo(emptyTable);
        assertThat(localized.getString("Dialog.cancel.button")).isEqualTo(cancel);
    }
}
