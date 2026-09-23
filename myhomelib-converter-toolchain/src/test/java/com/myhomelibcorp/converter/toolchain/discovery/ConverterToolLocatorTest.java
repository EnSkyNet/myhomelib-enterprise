package com.myhomelibcorp.converter.toolchain.discovery;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ConverterToolLocatorTest {
    @TempDir Path tempDir;

    @Test
    void rejectsDirectSymlinkExecutableWhenPlatformSupportsIt() throws Exception {
        Path real = tempDir.resolve("tool");
        Files.writeString(real, "tool");
        real.toFile().setExecutable(true);
        Path link = tempDir.resolve("tool-link");
        try {
            Files.createSymbolicLink(link, real.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }

        ConverterToolLocator locator = new ConverterToolLocator(emptySettings());
        assertThat(locator.validateCandidate(link)).isEmpty();
    }


    @Test
    void rejectsExecutableBehindSymlinkedParentWhenPlatformSupportsIt() throws Exception {
        Path realDir = tempDir.resolve("real-bin");
        Files.createDirectories(realDir);
        Path real = realDir.resolve("pandoc");
        Files.writeString(real, "tool");
        real.toFile().setExecutable(true);
        Path linkDir = tempDir.resolve("linked-bin");
        try {
            Files.createSymbolicLink(linkDir, realDir.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }

        ConverterToolLocator locator = new ConverterToolLocator(emptySettings());
        assertThat(locator.validateCandidate(linkDir.resolve("pandoc"))).isEmpty();
    }

    @Test
    void rejectsExplicitExecutableWithUnexpectedFilename() throws Exception {
        Path wrong = tempDir.resolve("not-pandoc");
        Files.writeString(wrong, "tool");
        wrong.toFile().setExecutable(true);

        ConverterToolLocator locator = new ConverterToolLocator(emptySettings());
        assertThat(locator.validateExplicit(wrong, "pandoc")).isEmpty();
    }


    @Test
    void locatesBundledConverterInsideJpackageApplicationDirectory() throws Exception {
        String previous = System.getProperty("myhomelib.launchDir");
        try {
            System.setProperty("myhomelib.launchDir", tempDir.toString());
            Path executable = tempDir.resolve("app/tools/converters/libmobi").resolve(platformExecutable("mobitool"));
            Files.createDirectories(executable.getParent());
            Files.writeString(executable, "tool");
            executable.toFile().setExecutable(true);

            ConverterToolLocator locator = new ConverterToolLocator(emptySettings());

            assertThat(locator.locate("libmobi", "mobitool"))
                    .contains(executable.toAbsolutePath().normalize());
        } finally {
            restore("myhomelib.launchDir", previous);
        }
    }

    @Test
    void locatesBundledConverterInsideLinuxJpackageApplicationDirectory() throws Exception {
        String previous = System.getProperty("myhomelib.launchDir");
        try {
            Path launcherDir = tempDir.resolve("bin");
            Files.createDirectories(launcherDir);
            System.setProperty("myhomelib.launchDir", launcherDir.toString());
            Path executable = tempDir.resolve("lib/app/tools/converters/libmobi").resolve(platformExecutable("mobitool"));
            Files.createDirectories(executable.getParent());
            Files.writeString(executable, "tool");
            executable.toFile().setExecutable(true);

            ConverterToolLocator locator = new ConverterToolLocator(emptySettings());

            assertThat(locator.locate("libmobi", "mobitool"))
                    .contains(executable.toAbsolutePath().normalize());
        } finally {
            restore("myhomelib.launchDir", previous);
        }
    }

    private static String platformExecutable(String base) {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).contains("win")
                ? base + ".exe" : base;
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }

    private static ApplicationSettingsPort emptySettings() {
        return new ApplicationSettingsPort() {
            @Override public String get(String key, String defaultValue) { return defaultValue; }
            @Override public void put(String key, String value) { }
            @Override public void remove(String key) { }
            @Override public Map<String, String> findByPrefix(String prefix) { return Map.of(); }
        };
    }
}
