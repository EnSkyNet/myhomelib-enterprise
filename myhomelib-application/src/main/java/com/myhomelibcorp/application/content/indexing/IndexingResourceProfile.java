package com.myhomelibcorp.application.content.indexing;

public enum IndexingResourceProfile {
    ECO(1, 2L * 1024 * 1024, true),
    BALANCED(4, 16L * 1024 * 1024, true),
    FAST(8, 0L, false);

    private final int maxThreads;
    private final long ioBytesPerSecond;
    private final boolean defaultPauseOnBattery;

    IndexingResourceProfile(int maxThreads, long ioBytesPerSecond, boolean defaultPauseOnBattery) {
        this.maxThreads = maxThreads;
        this.ioBytesPerSecond = ioBytesPerSecond;
        this.defaultPauseOnBattery = defaultPauseOnBattery;
    }

    public int workerThreads(int processors) {
        int cpu = Math.max(1, processors);
        return switch (this) {
            case ECO -> 1;
            case BALANCED -> Math.max(1, Math.min(maxThreads, Math.max(1, cpu / 2)));
            case FAST -> Math.max(1, Math.min(maxThreads, Math.max(1, cpu - 1)));
        };
    }
    public long ioBytesPerSecond() { return ioBytesPerSecond; }
    public boolean defaultPauseOnBattery() { return defaultPauseOnBattery; }
}
