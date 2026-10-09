package com.myhomelibcorp.epub.optimizer.archive;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Shared OCF ZIP writing invariants used by all EPUB rewrite paths. */
public final class EpubZipWriterSupport {
    private static final byte[] MIMETYPE = "application/epub+zip".getBytes(StandardCharsets.US_ASCII);

    private EpubZipWriterSupport() {
    }

    /**
     * Writes the mandatory first OCF {@code mimetype} entry as STORED with no extra field.
     * A {@code null} timestamp preserves the ZIP implementation default timestamp semantics.
     */
    public static void writeMimetype(ZipOutputStream out, LocalDateTime timestamp) throws IOException {
        Objects.requireNonNull(out, "out");
        CRC32 crc = new CRC32();
        crc.update(MIMETYPE);

        ZipEntry entry = new ZipEntry("mimetype");
        entry.setMethod(ZipEntry.STORED);
        entry.setSize(MIMETYPE.length);
        entry.setCompressedSize(MIMETYPE.length);
        entry.setCrc(crc.getValue());
        entry.setExtra(null);
        if (timestamp != null) {
            entry.setTime(timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli());
        }

        out.putNextEntry(entry);
        out.write(MIMETYPE);
        out.closeEntry();
    }

    public static int mimetypeLength() {
        return MIMETYPE.length;
    }
}
