package com.myhomelibcorp.application.extension;

import com.myhomelibcorp.application.export.DeviceTargetProfile;

import java.nio.file.Path;
import java.util.Optional;

/** Runtime extension that can recognize a mounted reading device and describe its export preferences. */
public interface DeviceProfileDetector {
    String id();
    Optional<DeviceTargetProfile> detect(Path mountRoot);
}
