package com.myhomelibcorp.organizer.analysis;

/** Immutable, user-facing result of a read-only author-folder name inspection. */
public final class AuthorFolderOrderPreview {
    private AuthorFolderOrderPreview() { }
    public enum Status { PROPOSAL, ALREADY_CORRECT, NO_BOOKS, AMBIGUOUS, TARGET_EXISTS }
    public record Result(String currentName, String proposedName, long matchingBooks, Status status) { }
}
