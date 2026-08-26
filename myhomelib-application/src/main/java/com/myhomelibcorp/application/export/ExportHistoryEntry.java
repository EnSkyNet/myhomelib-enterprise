package com.myhomelibcorp.application.export;

import com.myhomelibcorp.application.dto.ExportRequest;

import java.time.Instant;

/** One bounded persisted export-run summary. */
public record ExportHistoryEntry(
        String id,
        Instant completedAt,
        String profileName,
        String destination,
        ExportRequest.ExportFormat format,
        int requested,
        int exported,
        int skipped,
        int failed,
        boolean cancelled,
        long durationMs
) { }
