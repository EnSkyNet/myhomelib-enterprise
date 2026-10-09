package com.myhomelibcorp.shared.archive;

import java.io.IOException;

/** Common declared-size and compression-ratio checks for archive entries. */
public final class ArchiveEntrySafety {
    private ArchiveEntrySafety() { }

    public static void check(String name, boolean directory, long size, long compressedSize, String role)
            throws IOException {
        if (directory) return;
        if (ArchiveSafetyLimits.declaredEntryTooLarge(size)) {
            throw new IOException(role + " exceeds archive safety limit: " + name);
        }
        if (ArchiveSafetyLimits.exceedsCompressionRatio(size, compressedSize)) {
            throw new IOException(role + " has suspicious compression ratio: " + name);
        }
    }
}
