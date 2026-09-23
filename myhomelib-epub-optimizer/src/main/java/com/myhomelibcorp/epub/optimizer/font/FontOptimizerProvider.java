package com.myhomelibcorp.epub.optimizer.font;

import java.nio.file.Path;
import java.util.Optional;

/** Optional audited font subsetting backend; absence means fonts are retained unchanged. */
public interface FontOptimizerProvider {
    String id();
    boolean isAvailable();
    Optional<FontSubsetResult> subset(Path sourceFont, FontSubsetRequest request, Path workspace) throws Exception;
}
