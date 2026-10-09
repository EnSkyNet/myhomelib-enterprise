package com.myhomelibcorp.application.comic.duplicates;

import java.util.List;

public record ComicDuplicateScanResult(List<DuplicatePageGroup> groups, List<ComicPageScanFailure> failures,
                                       int scannedPages, int totalPages) {
    public ComicDuplicateScanResult {
        groups = groups == null ? List.of() : List.copyOf(groups);
        failures = failures == null ? List.of() : List.copyOf(failures);
    }
}
