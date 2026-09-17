package com.myhomelibcorp.application.ai;

import java.util.Objects;

/** Bounded provider-neutral request. Book content remains optional and requires explicit sharing consent. */
public record AiRequest(
        String bookId,
        AiOperation operation,
        String prompt,
        String bookContent
) {
    public static final int MAX_PROMPT_CHARS = 16_384;
    public static final int MAX_BOOK_CONTENT_CHARS = 524_288;

    public AiRequest {
        bookId = cleanRequired(bookId, "bookId", 128);
        operation = Objects.requireNonNull(operation, "operation");
        prompt = cleanRequired(prompt, "prompt", MAX_PROMPT_CHARS);
        bookContent = cleanOptional(bookContent, MAX_BOOK_CONTENT_CHARS);
    }

    public boolean sharesBookContent() {
        return !bookContent.isEmpty();
    }

    private static String cleanRequired(String value, String name, int max) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        String cleaned = value.trim();
        if (cleaned.length() > max) throw new IllegalArgumentException(name + " exceeds " + max + " characters");
        return cleaned;
    }

    private static String cleanOptional(String value, int max) {
        if (value == null || value.isBlank()) return "";
        String cleaned = value.trim();
        if (cleaned.length() > max) throw new IllegalArgumentException("bookContent exceeds " + max + " characters");
        return cleaned;
    }
}
