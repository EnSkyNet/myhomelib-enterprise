package com.myhomelibcorp.application.port.out.repository;

/**
 * Natural-key candidate used for bounded duplicate resolution during imports.
 * The values intentionally preserve the existing catalogue matching semantics:
 * exact title plus the incoming first author's last name.
 */
public interface DuplicateBookCandidate {
    String title();
    String firstAuthorLastName();

    static DuplicateBookCandidate of(String title, String firstAuthorLastName) {
        return new DuplicateBookCandidateRecord(title, firstAuthorLastName);
    }
}

/** Internal record implementation. */
record DuplicateBookCandidateRecord(
        String title,
        String firstAuthorLastName
) implements DuplicateBookCandidate {
    DuplicateBookCandidateRecord {
        title = title == null ? "" : title;
        firstAuthorLastName = firstAuthorLastName == null ? "" : firstAuthorLastName;
    }
}