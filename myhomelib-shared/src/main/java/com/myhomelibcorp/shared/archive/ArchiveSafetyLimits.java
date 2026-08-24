package com.myhomelibcorp.shared.archive;

/**
 * Single archive-safety policy shared by import, Reader, cover/export and MCP code.
 * Keep these values centralized so a book accepted by one path is not unexpectedly
 * rejected by another and so archive-bomb protection stays consistent.
 */
public final class ArchiveSafetyLimits {
    private ArchiveSafetyLimits() { }

    /** Maximum uncompressed size of one archive entry: 512 MiB. */
    public static final long MAX_ENTRY_BYTES = 512L * 1024L * 1024L;

    /** Maximum cumulative decompressed bytes consumed while indexing one archive: 1 GiB. */
    public static final long MAX_TOTAL_DECOMPRESSED_BYTES = 1024L * 1024L * 1024L;

    /** Maximum number of archive members inspected in one operation. */
    public static final int MAX_ENTRY_COUNT = 100_000;

    /** Maximum suspicious uncompressed/compressed ratio when both sizes are known. */
    public static final long MAX_COMPRESSION_RATIO = 100L;

    /** Commons Compress 7z decoder memory ceiling: 128 MiB in KiB. */
    public static final int SEVEN_Z_MEMORY_LIMIT_KIB = 128 * 1024;

    public static boolean declaredEntryTooLarge(long size) {
        return size >= 0 && size > MAX_ENTRY_BYTES;
    }
}
