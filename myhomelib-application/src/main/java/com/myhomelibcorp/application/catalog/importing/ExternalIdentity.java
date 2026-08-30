package com.myhomelibcorp.application.catalog.importing;

public record ExternalIdentity(String scheme, String value) {
    public ExternalIdentity {
        scheme = scheme == null ? "" : scheme.trim();
        value = value == null ? "" : value.trim();
    }

    public boolean usable() {
        return !scheme.isBlank() && !value.isBlank();
    }
}
