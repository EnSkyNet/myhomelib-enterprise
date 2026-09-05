package com.myhomelibcorp;

import com.myhomelibcorp.shared.util.AppPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class AppPathsLaunchDirTest {
    private final String oldExplicit = System.getProperty("myhomelib.launchDir");
    private final String oldJpackageVersion = System.getProperty("jpackage.app-version");
    private final String oldUserDir = System.getProperty("user.dir");

    @AfterEach
    void restoreProperties() {
        restore("myhomelib.launchDir", oldExplicit);
        restore("jpackage.app-version", oldJpackageVersion);
        restore("user.dir", oldUserDir);
    }

    @Test
    void explicitLaunchDirStillHasPriority() {
        Path explicit = Path.of("target", "explicit-launch-dir").toAbsolutePath().normalize();
        System.setProperty("myhomelib.launchDir", explicit.toString());
        System.setProperty("jpackage.app-version", "7.1.0-test");
        assertEquals(explicit, AppPaths.launchDir());
    }

    @Test
    void normalJvmFallsBackToUserDir() {
        Path cwd = Path.of("target", "probe-cwd").toAbsolutePath().normalize();
        System.clearProperty("myhomelib.launchDir");
        System.clearProperty("jpackage.app-version");
        System.setProperty("user.dir", cwd.toString());
        assertEquals(cwd, AppPaths.launchDir());
    }

    @Test
    void jpackageRuntimeUsesNativeProcessDirectoryInsteadOfUserDir() {
        String command = ProcessHandle.current().info().command().orElseThrow();
        Path expected = Path.of(command).toAbsolutePath().normalize().getParent();
        Path unrelatedCwd = Path.of("target", "unrelated-cwd").toAbsolutePath().normalize();

        System.clearProperty("myhomelib.launchDir");
        System.setProperty("jpackage.app-version", "7.1.0-test");
        System.setProperty("user.dir", unrelatedCwd.toString());

        assertEquals(expected, AppPaths.launchDir());
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
