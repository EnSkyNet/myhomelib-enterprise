package com.myhomelibcorp.organizer.policy;

public record CanonicalFormatDecision(String targetFormat, boolean conversionRequired, boolean supported, String reason) {
    public CanonicalFormatDecision { targetFormat=targetFormat==null?"":targetFormat; reason=reason==null?"":reason; }
}
