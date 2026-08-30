package com.myhomelibcorp.application.catalog.importing;

import java.util.List;

public record CatalogPerson(
        String firstName,
        String middleName,
        String lastName,
        String nickname,
        String displayName,
        String disambiguation,
        List<ExternalIdentity> identities
) {
    public CatalogPerson {
        firstName = safe(firstName);
        middleName = safe(middleName);
        lastName = safe(lastName);
        nickname = safe(nickname);
        displayName = safe(displayName);
        disambiguation = safe(disambiguation);
        identities = identities == null ? List.of() : List.copyOf(identities);
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
}
