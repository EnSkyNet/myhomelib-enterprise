package com.myhomelibcorp.epub.optimizer.image;

import com.myhomelibcorp.epub.optimizer.EpubOptimizationProfile;
import com.myhomelibcorp.epub.optimizer.profile.ReadingSystemProfile;

public record ImageOptimizationRequest(ImageResourceAnalysis source, EpubOptimizationProfile profile,
                                       ReadingSystemProfile readingSystem, int maxWidth, int maxHeight,
                                       long minimumSavingsBytes, double minimumSavingsRatio,
                                       double perceptualQualityFloor) { }
