package com.myhomelibcorp.epub.optimizer.font;

public record FontResourceAnalysis(String path, String mediaType, long byteSize, int referenceCount,
                                   boolean encrypted, boolean variableFont, boolean variableFontKnown) {
    public FontResourceAnalysis { path = path == null ? "" : path; mediaType = mediaType == null ? "" : mediaType; byteSize = Math.max(0L, byteSize); referenceCount = Math.max(0, referenceCount); }
}
