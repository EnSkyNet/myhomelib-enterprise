package com.myhomelibcorp.epub.optimizer.font;

import com.myhomelibcorp.epub.optimizer.EpubOptimizationProfile;
import com.myhomelibcorp.epub.optimizer.profile.ReadingSystemProfile;

import java.util.Set;

public record FontSubsetRequest(FontResourceAnalysis source, Set<Integer> requiredCodePoints,
                                EpubOptimizationProfile profile, ReadingSystemProfile readingSystem) {
    public FontSubsetRequest { requiredCodePoints = Set.copyOf(requiredCodePoints == null ? Set.of() : requiredCodePoints); }
}
