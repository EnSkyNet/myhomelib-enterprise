package com.myhomelibcorp.organizer.apply;

import java.nio.file.Path;

/**
 * Provider-neutral validation bridge used by Organizer after any converter produced a staged artifact.
 * Implementations should delegate to the same format validators used by the conversion toolchain.
 */
public interface OrganizerOutputValidationPort {
    void validate(Path file, String targetFormat, long maxOutputBytes) throws Exception;
}
