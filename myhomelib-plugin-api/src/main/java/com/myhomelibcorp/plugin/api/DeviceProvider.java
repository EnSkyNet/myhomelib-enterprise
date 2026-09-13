package com.myhomelibcorp.plugin.api;

import java.nio.file.Path;
import java.util.Optional;

/** Detects a mounted reading device and exposes its preferred book formats/destination. */
public interface DeviceProvider {
    String id();
    Optional<DeviceProfile> detect(Path mountRoot);
}
