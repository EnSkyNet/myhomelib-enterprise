package com.myhomelibcorp.organizer.detect;

public record DetectedBookFormat(String format, String mediaType, DetectionConfidence confidence,
                                 String extension, boolean archive, String reason) {
    public DetectedBookFormat {
        format = format == null ? "unknown" : format.trim().toLowerCase();
        mediaType = mediaType == null ? "application/octet-stream" : mediaType;
        confidence = confidence == null ? DetectionConfidence.UNKNOWN : confidence;
        extension = extension == null ? "" : extension.trim().toLowerCase();
        reason = reason == null ? "" : reason;
    }
    public boolean known() { return !"unknown".equals(format); }
}
