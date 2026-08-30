package com.myhomelibcorp.domain.model.author;

/**
 * Structured author-name lookup key. Never serialize this key with a delimiter:
 * real author names may legally contain characters such as '|'.
 */
public record AuthorNameKey(String firstName, String middleName, String lastName) {
    public AuthorNameKey {
        firstName = normalize(firstName);
        middleName = normalize(middleName);
        lastName = normalize(lastName);
    }

    public static AuthorNameKey of(Author author) {
        return new AuthorNameKey(author.getFirstName(), author.getMiddleName(), author.getLastName());
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim();
    }
}
