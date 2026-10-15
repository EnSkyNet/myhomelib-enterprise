package com.myhomelibcorp;

import com.myhomelibcorp.shared.util.AppPaths;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.when;

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
        Path executable = Path.of("target", "native-app", "MyHomeLib").toAbsolutePath().normalize();
        configurePackagedRuntime();
        ProcessHandle process = mock(ProcessHandle.class);
        ProcessHandle.Info info = mock(ProcessHandle.Info.class);
        when(process.info()).thenReturn(info);
        when(info.command()).thenReturn(Optional.of(executable.toString()));
        try (var handles = mockStatic(ProcessHandle.class)) {
            handles.when(ProcessHandle::current).thenReturn(process);
            assertEquals(executable.getParent(), AppPaths.launchDir());
        }
    }

    @Test
    void packagedRuntimeFallsBackWhenCommandMetadataIsUnavailable() {
        Path cwd = configurePackagedRuntime();
        ProcessHandle process = mock(ProcessHandle.class);
        ProcessHandle.Info info = mock(ProcessHandle.Info.class);
        when(process.info()).thenReturn(info);
        when(info.command()).thenReturn(Optional.empty());
        try (var handles = mockStatic(ProcessHandle.class)) {
            handles.when(ProcessHandle::current).thenReturn(process);
            assertEquals(cwd, AppPaths.launchDir());
        }
    }

    @Test
    void packagedRuntimeFallsBackWhenProcessAccessIsDenied() {
        Path cwd = configurePackagedRuntime();
        try (var handles = mockStatic(ProcessHandle.class)) {
            handles.when(ProcessHandle::current).thenThrow(new SecurityException("Process metadata denied"));
            assertEquals(cwd, AppPaths.launchDir());
        }
    }

    private static Path configurePackagedRuntime() {
        Path cwd = Path.of("target", "unrelated-cwd").toAbsolutePath().normalize();
        System.clearProperty("myhomelib.launchDir");
        System.setProperty("jpackage.app-version", "7.1.0-test");
        System.setProperty("user.dir", cwd.toString());
        return cwd;
    }

    private static void restore(String key, String value) {
        if (value == null) System.clearProperty(key);
        else System.setProperty(key, value);
    }
}
