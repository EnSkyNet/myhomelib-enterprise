package com.myhomelibcorp.converter.toolchain.discovery;

public record ToolVersion(String raw) {
    public ToolVersion { raw = raw == null ? "" : raw.strip(); }
    public boolean known() { return !raw.isBlank(); }
}
