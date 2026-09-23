package com.myhomelibcorp.epub.optimizer.image;

import com.myhomelibcorp.epub.optimizer.profile.ImageCodec;

import java.nio.file.Path;
import java.util.OptionalDouble;

public record ImageOptimizationCandidate(Path file, ImageCodec codec, int width, int height,
                                         boolean alpha, boolean animated, long byteSize,
                                         boolean lossless, OptionalDouble perceptualScore) {
    public ImageOptimizationCandidate {
        perceptualScore = perceptualScore == null ? OptionalDouble.empty() : perceptualScore;
        width = Math.max(0, width); height = Math.max(0, height); byteSize = Math.max(0L, byteSize);
    }
}
