package com.myhomelibcorp.application.port.out.exporter;

import com.myhomelibcorp.application.dto.ExportRequest;
import java.io.IOException;
import java.nio.file.Path;
import java.util.function.BooleanSupplier;

/** Streams prepared book files into a new ZIP/7z archive in a private staging workspace. */
public interface DeviceArchivePort {
    /** RAR can be offered only with an installed licensed external archiver. */
    default boolean canWrite(ExportRequest.ArchiveFormat format) {
        return format == ExportRequest.ArchiveFormat.ZIP
                || format == ExportRequest.ArchiveFormat.SEVEN_Z;
    }

    void pack(Path sourceRoot, Path stagedArchive, ExportRequest.ArchiveFormat format,
              ExportRequest.CompressionLevel strength, BooleanSupplier cancelled) throws IOException;
}
