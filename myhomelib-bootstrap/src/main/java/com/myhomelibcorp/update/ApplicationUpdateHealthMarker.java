package com.myhomelibcorp.update;

import com.myhomelibcorp.shared.util.AtomicFileSupport;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;

/** Writes the one-shot health evidence requested by the external updater helper. */
public final class ApplicationUpdateHealthMarker {
    private ApplicationUpdateHealthMarker() { }

    public static void markHealthyIfRequested() {
        String requested = System.getenv(ApplicationUpdateInstaller.HEALTH_MARKER_ENV);
        if (requested == null || requested.isBlank()) return;
        try {
            Path marker = Path.of(requested).toAbsolutePath().normalize();
            AtomicFileSupport.writeStringReplacing(marker, "healthy=" + Instant.now() + System.lineSeparator(), StandardCharsets.UTF_8);
        } catch (Exception failure) {
            throw new IllegalStateException("Cannot publish application-update health marker", failure);
        }
    }
}
