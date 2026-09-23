package com.myhomelibcorp.organizer.policy;

import com.myhomelibcorp.epub.optimizer.EpubOptimizationProfile;

import java.util.List;

/** Built-in Organizer profiles. Names are internal fallbacks; UI must render localized labels by id. */
public final class OrganizerProfiles {
    public static final String SAFE = "safe";
    public static final String EPUB_LIBRARY = "epub-library";
    public static final String KEEP_ORIGINALS = "keep-originals";
    public static final String RENAME_ONLY = "rename-only";
    public static final String ANALYZE_ONLY = "analyze-only";

    private OrganizerProfiles() { }

    public static List<OrganizerProfile> builtIns() {
        int analysisThreads = Math.min(4, Runtime.getRuntime().availableProcessors());
        return List.of(
                new OrganizerProfile(SAFE, "Safe",
                        "{Author}/{?Series:{Series}/}", "{?Series:{SeriesNo:00} - }{Title}.{ext}",
                        "epub", PdfPolicy.KEEP_PDF, OriginalRetentionPolicy.KEEP_ORIGINAL,
                        OrganizerDuplicatePolicy.REVIEW, true, true, false,
                        analysisThreads, 1, EpubOptimizationProfile.COMPATIBLE_EPUB33),
                new OrganizerProfile(EPUB_LIBRARY, "EPUB library",
                        "{Author}/{?Series:{Series}/}", "{?Series:{SeriesNo:00} - }{Title}.{ext}",
                        "epub", PdfPolicy.KEEP_PDF, OriginalRetentionPolicy.KEEP_ORIGINAL,
                        OrganizerDuplicatePolicy.REVIEW, true, true, false,
                        analysisThreads, 2, EpubOptimizationProfile.BALANCED_EPUB33),
                new OrganizerProfile(KEEP_ORIGINALS, "Keep originals",
                        "{Author}/{?Series:{Series}/}", "{?Series:{SeriesNo:00} - }{Title}.{ext}",
                        "epub", PdfPolicy.KEEP_PDF, OriginalRetentionPolicy.KEEP_ORIGINAL,
                        OrganizerDuplicatePolicy.KEEP_ALL, true, true, false,
                        analysisThreads, 2, EpubOptimizationProfile.BALANCED_EPUB33),
                new OrganizerProfile(RENAME_ONLY, "Rename only",
                        "{Author}/{?Series:{Series}/}", "{?Series:{SeriesNo:00} - }{Title}.{ext}",
                        "original", PdfPolicy.KEEP_PDF, OriginalRetentionPolicy.KEEP_ORIGINAL,
                        OrganizerDuplicatePolicy.REVIEW, true, true, false,
                        analysisThreads, 1, EpubOptimizationProfile.NONE),
                new OrganizerProfile(ANALYZE_ONLY, "Analyze only",
                        "{Author}/{?Series:{Series}/}", "{?Series:{SeriesNo:00} - }{Title}.{ext}",
                        "epub", PdfPolicy.KEEP_PDF, OriginalRetentionPolicy.KEEP_ORIGINAL,
                        OrganizerDuplicatePolicy.REVIEW, true, true, false,
                        analysisThreads, 1, EpubOptimizationProfile.BALANCED_EPUB33)
        );
    }

    public static OrganizerProfile byId(String id) {
        if (id != null) {
            for (OrganizerProfile profile : builtIns()) {
                if (profile.id().equalsIgnoreCase(id.trim())) return profile;
            }
        }
        return builtIns().getFirst();
    }
}
