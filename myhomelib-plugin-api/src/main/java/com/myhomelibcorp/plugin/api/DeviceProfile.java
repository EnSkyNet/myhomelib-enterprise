package com.myhomelibcorp.plugin.api;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

public record DeviceProfile(String deviceId, String displayName, List<String> preferredFormats, Path destinationRoot) {
    public DeviceProfile {
        if (deviceId == null || deviceId.isBlank()) throw new IllegalArgumentException("deviceId is required");
        if (displayName == null || displayName.isBlank()) throw new IllegalArgumentException("displayName is required");
        preferredFormats = List.copyOf(Objects.requireNonNull(preferredFormats, "preferredFormats"));
        Objects.requireNonNull(destinationRoot, "destinationRoot");
    }
}
