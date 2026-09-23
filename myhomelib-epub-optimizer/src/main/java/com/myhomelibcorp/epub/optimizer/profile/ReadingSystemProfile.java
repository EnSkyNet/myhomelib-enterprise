package com.myhomelibcorp.epub.optimizer.profile;

import java.util.Set;

public interface ReadingSystemProfile {
    String id();
    EpubTargetStandard targetStandard();
    Set<ImageCodec> supportedImageCodecs();
    boolean supportsWoff2();
    int preferredMaxImageWidth();
    int preferredMaxImageHeight();
    long preferredMaxResourceBytes();
}
