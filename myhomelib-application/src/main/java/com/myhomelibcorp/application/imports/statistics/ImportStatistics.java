package com.myhomelibcorp.application.imports.statistics;

import lombok.Getter;
import lombok.ToString;

import java.util.concurrent.atomic.AtomicLong;

@Getter
@ToString
public class ImportStatistics {
    private final AtomicLong imported = new AtomicLong(0);
    private final AtomicLong skipped = new AtomicLong(0);
    private final AtomicLong duplicates = new AtomicLong(0);
    private final AtomicLong errors = new AtomicLong(0);
    private final long startTime = System.currentTimeMillis();

    public void incrementImported() { imported.incrementAndGet(); }
    public void incrementImported(int count) { imported.addAndGet(count); }
    public void incrementSkipped() { skipped.incrementAndGet(); }
    public void incrementDuplicates() { duplicates.incrementAndGet(); }
    public void incrementErrors() { errors.incrementAndGet(); }

    public long getDurationMs() {
        return System.currentTimeMillis() - startTime;
    }
}