package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.settings.UiPreferenceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ThemeCacheBoundedCleanupTest {
    @TempDir Path temp;

    @Test
    void removesOldGeneratedCssWithoutRetainingAListOfEveryCacheEntry() throws Exception {
        final String prefix = "myhomelib-theme-generated-";
        for (int i = 0; i < 300; i++) {
            Path css = Files.writeString(temp.resolve(prefix + i + ".css"), "synthetic css");
            Files.setLastModifiedTime(css, FileTime.fromMillis(1_700_000_000_000L + i * 1_000L));
        }
        Path unrelated = Files.writeString(temp.resolve("keep-me.css"), "not generated");
        Files.createDirectory(temp.resolve(prefix + "a-directory.css"));

        ApplicationThemeService service = new ApplicationThemeService(mock(UiPreferenceService.class));
        service.pruneGeneratedCss(temp);

        for (int i = 0; i < 292; i++) {
            assertThat(temp.resolve(prefix + i + ".css")).doesNotExist();
        }
        for (int i = 292; i < 300; i++) {
            assertThat(temp.resolve(prefix + i + ".css")).isRegularFile();
        }
        assertThat(unrelated).isRegularFile();
        assertThat(temp.resolve(prefix + "a-directory.css")).isDirectory();
    }

    @Test
    void keepsEveryGeneratedFileWhenCacheIsWithinEightFileLimit() throws Exception {
        Path first = Files.writeString(temp.resolve("myhomelib-theme-generated-old.css"), "old");
        Path second = Files.writeString(temp.resolve("myhomelib-theme-generated-new.css"), "new");
        var service = new ApplicationThemeService(mock(UiPreferenceService.class));
        service.pruneGeneratedCss(temp);
        assertThat(first).isRegularFile();
        assertThat(second).isRegularFile();
    }

    @Test
    void symlinkedGeneratedEntryNeverDeletesOutsideFile() throws Exception {
        Path outside = Files.writeString(temp.resolve("outside.css"), "foreign");
        Path link = temp.resolve("myhomelib-theme-generated-linked.css");
        try {
            Files.createSymbolicLink(link, outside);
        } catch (java.io.IOException | UnsupportedOperationException | SecurityException unavailable) {
            // Symlinks require Windows Developer Mode or appropriate privilege on some CI hosts.
            return;
        }
        for (int i = 0; i < 20; i++) {
            Path css = Files.writeString(temp.resolve("myhomelib-theme-generated-" + i + ".css"), "css");
            Files.setLastModifiedTime(css, FileTime.fromMillis(1_700_000_000_000L + i * 1_000L));
        }
        var service = new ApplicationThemeService(mock(UiPreferenceService.class));
        service.pruneGeneratedCss(temp);
        assertThat(outside).hasContent("foreign");
        assertThat(Files.isSymbolicLink(link)).isTrue();
    }
}
