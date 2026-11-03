package com.myhomelibcorp.application.port.out.exporter;

import com.myhomelibcorp.application.dto.ExportRequest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Streams prepared book files into a new ZIP/7z archive in a private staging workspace. */
public interface DeviceArchivePort {
    void pack(Path sourceRoot, Path stagedArchive, ExportRequest.ArchiveFormat format,
              ExportRequest.CompressionLevel strength, BooleanSupplier cancelled) throws IOException;
}
