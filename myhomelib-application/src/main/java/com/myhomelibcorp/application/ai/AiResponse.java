package com.myhomelibcorp.application.ai;

/** Sanitized provider response returned to host UI/code. */
public record AiResponse(String text) {
    public static final int MAX_RESPONSE_CHARS = 1_048_576;

    public AiResponse {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("AI response text is required");
        text = text.trim();
        if (text.length() > MAX_RESPONSE_CHARS) {
            throw new IllegalArgumentException("AI response exceeds " + MAX_RESPONSE_CHARS + " characters");
        }
    }
}
