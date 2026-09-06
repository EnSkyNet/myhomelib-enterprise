package com.myhomelibcorp.shared.security;

import java.nio.file.Path;

public record SecretStoreContext(Path configDir, boolean portableMode, String osName, String userName) {
    public SecretStoreContext {
        if (configDir == null) throw new IllegalArgumentException("configDir must not be null");
        osName = osName == null ? "" : osName;
        userName = userName == null || userName.isBlank() ? "unknown" : userName;
    }

    public boolean isWindows() { return osName.toLowerCase(java.util.Locale.ROOT).startsWith("windows"); }
    public boolean isMac() { return osName.toLowerCase(java.util.Locale.ROOT).contains("mac"); }
    public boolean isLinux() { return osName.toLowerCase(java.util.Locale.ROOT).contains("linux"); }
}
