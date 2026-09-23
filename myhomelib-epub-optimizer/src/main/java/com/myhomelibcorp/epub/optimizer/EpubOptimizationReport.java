package com.myhomelibcorp.epub.optimizer;

import com.myhomelibcorp.epub.optimizer.packaging.ZipCompressionBackend;
import com.myhomelibcorp.epub.optimizer.profile.EpubTargetStandard;

import java.util.List;

public record EpubOptimizationReport(
        long sourceSize,
        long resultSize,
        long savedBytes,
        double savedPercent,
        int removedOrphans,
        int deduplicatedResources,
        long imagesBeforeBytes,
        long imagesAfterBytes,
        int imagesConverted,
        int imagesResized,
        long fontsBeforeBytes,
        long fontsAfterBytes,
        int fontsSubset,
        long markupBeforeBytes,
        long markupAfterBytes,
        long zipBeforeBytes,
        long zipAfterBytes,
        ZipCompressionBackend compressionBackend,
        EpubOptimizationProfile profile,
        EpubTargetStandard targetStandard,
        String readingSystemProfile,
        String epubCheckStatus,
        String readerReopenStatus,
        String semanticVerificationStatus,
        List<String> warnings) {
    public EpubOptimizationReport { warnings = List.copyOf(warnings == null ? List.of() : warnings); }
}
