package com.myhomelibcorp.infrastructure.epub;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class EpubCheckToolCommandFactoryTest {
    @TempDir Path tempDir;

    @Test
    void rejectsExplicitFileWithUnexpectedName() throws Exception {
        Path wrong = tempDir.resolve("validator.jar");
        Files.writeString(wrong, "not-a-real-jar");

        EpubCheckToolCommandFactory.ResolvedTool resolved = new EpubCheckToolCommandFactory().resolve(wrong.toString());

        assertThat(resolved.available()).isFalse();
        assertThat(resolved.source()).contains("does not look like EPUBCheck");
    }

    @Test
    void rejectsExplicitSymlinkWhenPlatformSupportsIt() throws Exception {
        Path real = tempDir.resolve("epubcheck.jar");
        Files.writeString(real, "jar");
        Path link = tempDir.resolve("epubcheck-link.jar");
        try {
            Files.createSymbolicLink(link, real.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }

        EpubCheckToolCommandFactory.ResolvedTool resolved = new EpubCheckToolCommandFactory().resolve(link.toString());

        assertThat(resolved.available()).isFalse();
    }
    @Test
    void rejectsExplicitEpubCheckBehindSymlinkedParentWhenPlatformSupportsIt() throws Exception {
        Path realDir = tempDir.resolve("real-tools");
        Files.createDirectories(realDir);
        Path real = realDir.resolve("epubcheck.jar");
        Files.writeString(real, "jar");
        Path linkDir = tempDir.resolve("linked-tools");
        try {
            Files.createSymbolicLink(linkDir, realDir.getFileName());
        } catch (UnsupportedOperationException | IOException | SecurityException unsupported) {
            return;
        }

        EpubCheckToolCommandFactory.ResolvedTool resolved = new EpubCheckToolCommandFactory()
                .resolve(linkDir.resolve("epubcheck.jar").toString());

        assertThat(resolved.available()).isFalse();
    }

    @Test
    void findsBundledJarInsideJpackageApplicationDirectory() throws Exception {
        String previous = System.getProperty("myhomelib.launchDir");
        try {
            System.setProperty("myhomelib.launchDir", tempDir.toString());
            Path jar = tempDir.resolve("app/tools/validators/epubcheck/epubcheck.jar");
            Files.createDirectories(jar.getParent());
            Files.writeString(jar, "jar");

            EpubCheckToolCommandFactory.ResolvedTool resolved = new EpubCheckToolCommandFactory().resolve("");

            assertThat(resolved.available()).isTrue();
            assertThat(Path.of(resolved.displayPath())).isEqualTo(jar.toAbsolutePath().normalize());
            assertThat(resolved.source()).isEqualTo("JAR");
        } finally {
            restore("myhomelib.launchDir", previous);
        }
    }

    @Test
    void findsBundledJarInsideLinuxJpackageApplicationDirectory() throws Exception {
        String previous = System.getProperty("myhomelib.launchDir");
        try {
            Path launcherDir = tempDir.resolve("bin");
            Files.createDirectories(launcherDir);
            System.setProperty("myhomelib.launchDir", launcherDir.toString());
            Path jar = tempDir.resolve("lib/app/tools/validators/epubcheck/epubcheck.jar");
            Files.createDirectories(jar.getParent());
            Files.writeString(jar, "jar");

            EpubCheckToolCommandFactory.ResolvedTool resolved = new EpubCheckToolCommandFactory().resolve("");

            assertThat(resolved.available()).isTrue();
            assertThat(Path.of(resolved.displayPath())).isEqualTo(jar.toAbsolutePath().normalize());
        } finally {
            restore("myhomelib.launchDir", previous);
        }
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }


    @Test
    void bundledWindowsStyleExeIsNotRequiredWhenJarIsAbsent() throws Exception {
        String previous = System.getProperty("myhomelib.launchDir");
        try {
            System.setProperty("myhomelib.launchDir", tempDir.toString());
            Path legacyExe = tempDir.resolve("tools/validators/epubcheck/epubcheck.exe");
            Files.createDirectories(legacyExe.getParent());
            Files.writeString(legacyExe, "legacy-wrapper");

            EpubCheckToolCommandFactory.ResolvedTool resolved = new EpubCheckToolCommandFactory().resolve("");

            assertThat(resolved.available()).isFalse();
        } finally {
            restore("myhomelib.launchDir", previous);
        }
    }


}
