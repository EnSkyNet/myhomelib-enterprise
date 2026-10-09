package com.myhomelibcorp.infrastructure.download;

import com.myhomelibcorp.application.util.SafeFilePublication;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Ownership-checked cleanup for HTTP resume metadata sidecars. */
final class ResumeMetadataFiles {
    private ResumeMetadataFiles() { }

    static void removeOwned(Path metadata, HttpResumeSupport.ResumeMetadata resume, String role) throws IOException {
        if (resume != null && resume.ownershipHash() != null && !resume.ownershipHash().isBlank()
                && Files.exists(metadata, LinkOption.NOFOLLOW_LINKS)) {
            SafeFilePublication.removeIfOwned(metadata, resume.ownershipHash(), role);
        }
    }
}
