package com.myhomelibcorp.organizer.detect;

import java.util.Locale;

public record DetectedBookFormat(String format, String mediaType, DetectionConfidence confidence,
                                 String extension, boolean archive, String reason) {
    public DetectedBookFormat {
        format = format == null ? "unknown" : format.trim().toLowerCase(Locale.ROOT);
        mediaType = mediaType == null ? "application/octet-stream" : mediaType;
        confidence = confidence == null ? DetectionConfidence.UNKNOWN : confidence;
        extension = extension == null ? "" : extension.trim().toLowerCase(Locale.ROOT);
        reason = reason == null ? "" : reason;
    }
    public boolean known() { return !"unknown".equals(format); }
}
