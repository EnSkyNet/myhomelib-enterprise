package com.myhomelibcorp.converter.toolchain.discovery;

import java.nio.file.Path;

public record ToolInstallation(String id, Path executable, ToolVersion version, boolean available, String diagnostic) {
    public static ToolInstallation unavailable(String id, String diagnostic) {
        return new ToolInstallation(id, null, new ToolVersion(""), false, diagnostic == null ? "" : diagnostic);
    }
}
