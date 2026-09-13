package com.myhomelibcorp.application.content.indexing;

public record IndexingPerformanceSettings(IndexingResourceProfile profile, boolean pauseOnBattery) {
    public IndexingPerformanceSettings {
        profile = profile == null ? IndexingResourceProfile.BALANCED : profile;
    }
    public int workerThreads(int processors) { return profile.workerThreads(processors); }
    public long ioBytesPerSecond() { return profile.ioBytesPerSecond(); }
}
