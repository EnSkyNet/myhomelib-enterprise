package com.myhomelibcorp.epub.optimizer.font;

import java.nio.file.Path;

public record FontSubsetResult(Path file, long byteSize, boolean glyphCoverageVerified, String mediaType) {
    public FontSubsetResult { byteSize = Math.max(0L, byteSize); mediaType = mediaType == null ? "" : mediaType; }
}
