package com.myhomelibcorp.application.port.out.repository;

/**
 * Natural-key candidate used for bounded duplicate resolution during imports.
 * The values intentionally preserve the existing catalogue matching semantics:
 * exact title plus the incoming first author's last name.
 */
public record DuplicateBookCandidate(String title, String firstAuthorLastName) {
    public DuplicateBookCandidate {
        title = title == null ? "" : title;
        firstAuthorLastName = firstAuthorLastName == null ? "" : firstAuthorLastName;
    }
}
