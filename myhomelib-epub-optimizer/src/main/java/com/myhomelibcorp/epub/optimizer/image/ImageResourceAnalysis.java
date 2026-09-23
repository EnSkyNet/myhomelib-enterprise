package com.myhomelibcorp.epub.optimizer.image;

import com.myhomelibcorp.epub.optimizer.profile.ImageCodec;

public record ImageResourceAnalysis(String path, ImageCodec codec, int width, int height, boolean dimensionsKnown,
                                    boolean alpha, boolean alphaKnown, boolean animated, boolean animationKnown,
                                    int bitDepth, long byteSize, boolean iccProfile, boolean exif,
                                    int orientation, ImageRole role, int referenceCount) {
    public ImageResourceAnalysis {
        path = path == null ? "" : path;
        width = Math.max(0, width); height = Math.max(0, height); bitDepth = Math.max(0, bitDepth);
        byteSize = Math.max(0L, byteSize); orientation = Math.max(0, orientation);
        role = role == null ? ImageRole.UNKNOWN : role; referenceCount = Math.max(0, referenceCount);
    }
}
