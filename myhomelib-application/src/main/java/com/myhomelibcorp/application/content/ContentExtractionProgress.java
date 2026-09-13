package com.myhomelibcorp.application.content;

/** Bounded progress event emitted while extracting one document. */
public record ContentExtractionProgress(String phase, long completed, long total) {
    public ContentExtractionProgress {
        phase = phase == null ? "" : phase.trim();
        completed = Math.max(0L, completed);
        total = Math.max(0L, total);
        if (total > 0L) completed = Math.min(completed, total);
    }

    public double fraction() {
        return total <= 0L ? 0.0 : (double) completed / (double) total;
    }
}
