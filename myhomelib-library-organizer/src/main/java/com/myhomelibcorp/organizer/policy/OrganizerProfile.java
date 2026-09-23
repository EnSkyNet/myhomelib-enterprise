package com.myhomelibcorp.organizer.policy;

import com.myhomelibcorp.epub.optimizer.EpubOptimizationProfile;

public record OrganizerProfile(String id, String name, String directoryTemplate, String filenameTemplate,
                               String canonicalReflowableFormat, PdfPolicy pdfPolicy,
                               OriginalRetentionPolicy originalRetention, OrganizerDuplicatePolicy duplicatePolicy,
                               boolean normalizeMetadata, boolean validateConvertedFiles,
                               boolean useExternalMetadataProviders, int maxParallelAnalysis,
                               int maxParallelConversions, EpubOptimizationProfile epubOptimizationProfile) {
    public OrganizerProfile {
        id = blank(id) ? "epub-library" : id; name = blank(name) ? "EPUB-бібліотека" : name;
        directoryTemplate = blank(directoryTemplate) ? "{Author}/{?Series:{Series}/}" : directoryTemplate;
        filenameTemplate = blank(filenameTemplate) ? "{?Series:{SeriesNo:00} - }{Title}.{ext}" : filenameTemplate;
        canonicalReflowableFormat = blank(canonicalReflowableFormat) ? "epub" : canonicalReflowableFormat.toLowerCase();
        pdfPolicy = pdfPolicy == null ? PdfPolicy.KEEP_PDF : pdfPolicy;
        originalRetention = originalRetention == null ? OriginalRetentionPolicy.KEEP_ORIGINAL : originalRetention;
        duplicatePolicy = duplicatePolicy == null ? OrganizerDuplicatePolicy.REVIEW : duplicatePolicy;
        maxParallelAnalysis = maxParallelAnalysis <= 0 ? Math.min(4, Runtime.getRuntime().availableProcessors()) : Math.min(16, maxParallelAnalysis);
        maxParallelConversions = maxParallelConversions <= 0 ? 1 : Math.min(4, maxParallelConversions);
        epubOptimizationProfile = epubOptimizationProfile == null ? EpubOptimizationProfile.BALANCED_EPUB33 : epubOptimizationProfile;
    }
    public static OrganizerProfile safeDefault() { return new OrganizerProfile("safe", "Безпечний", "{Author}/{?Series:{Series}/}",
            "{?Series:{SeriesNo:00} - }{Title}.{ext}", "epub", PdfPolicy.KEEP_PDF,
            OriginalRetentionPolicy.KEEP_ORIGINAL, OrganizerDuplicatePolicy.REVIEW, true, true, false, 0, 1,
            EpubOptimizationProfile.BALANCED_EPUB33); }
    private static boolean blank(String v) { return v == null || v.isBlank(); }
}
