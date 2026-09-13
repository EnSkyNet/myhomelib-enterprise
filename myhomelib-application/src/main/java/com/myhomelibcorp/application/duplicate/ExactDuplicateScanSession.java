package com.myhomelibcorp.application.duplicate;

/** Durable progress snapshot for an exact-duplicate scan. */
public record ExactDuplicateScanSession(
        String scanId,
        long total,
        long processed,
        long hashed,
        long skipped,
        long bytesProcessed,
        boolean resumed
) {
    public ExactDuplicateScanSession {
        if (scanId == null || scanId.isBlank()) throw new IllegalArgumentException("scanId is required");
        total = Math.max(0, total);
        processed = Math.max(0, processed);
        hashed = Math.max(0, hashed);
        skipped = Math.max(0, skipped);
        bytesProcessed = Math.max(0, bytesProcessed);
    }
}
