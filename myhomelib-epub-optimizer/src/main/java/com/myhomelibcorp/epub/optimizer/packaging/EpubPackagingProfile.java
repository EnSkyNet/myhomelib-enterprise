package com.myhomelibcorp.epub.optimizer.packaging;

public record EpubPackagingProfile(int deflateLevel, boolean deterministicTimestamps) {
    public EpubPackagingProfile {
        deflateLevel = Math.max(0, Math.min(9, deflateLevel));
    }
    public static EpubPackagingProfile compatible() { return new EpubPackagingProfile(9, true); }
}
