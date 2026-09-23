package com.myhomelibcorp.organizer.metadata;

public record NormalizedAuthor(String displayName, String givenName, String middleName,
                               String familyName, boolean structured) {
    public NormalizedAuthor {
        displayName = safe(displayName); givenName = safe(givenName); middleName = safe(middleName); familyName = safe(familyName);
    }
    private static String safe(String value) { return value == null ? "" : value; }
}
