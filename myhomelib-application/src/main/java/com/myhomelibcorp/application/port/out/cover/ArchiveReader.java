package com.myhomelibcorp.application.port.out.cover;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;

public interface ArchiveReader {
    boolean isArchive(Path file);
    List<String> listEntries(Path archivePath);
    Optional<InputStream> readEntry(Path archivePath, String entryName);
    Optional<InputStream> findFirstEntry(Path archivePath, Predicate<String> filter);

    /**
     * Streams one archive member into a caller-owned target without materializing the full
     * entry in memory. Implementations must enforce both the supplied byte ceiling and their
     * format-specific safety limits, observe cancellation between bounded reads and leave no
     * partially published target on failure.
     *
     * @return {@code true} when the requested member was found and published after a complete write
     */
    boolean materializeEntry(Path archivePath, String entryName, Path target, long maxBytes,
                             BooleanSupplier cancelled) throws IOException;
}
