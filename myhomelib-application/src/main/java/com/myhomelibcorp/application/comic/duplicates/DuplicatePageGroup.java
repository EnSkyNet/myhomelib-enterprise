package com.myhomelibcorp.application.comic.duplicates;

import java.util.List;

public record DuplicatePageGroup(String sha256, List<DuplicatePageEvidence> pages) {
    public DuplicatePageGroup { pages = pages == null ? List.of() : List.copyOf(pages); }
}
