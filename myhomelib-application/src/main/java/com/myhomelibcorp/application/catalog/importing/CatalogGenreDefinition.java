package com.myhomelibcorp.application.catalog.importing;

/**
 * A genre definition observed in a neutral catalog.
 *
 * <p>Only database-observed definitions are authoritative. FB2/FBD observations
 * may provide a usable code, but must not overwrite the destination catalog's
 * curated display name or category hierarchy.</p>
 */
public record CatalogGenreDefinition(
        String code,
        String description,
        String category,
        String translatedCode,
        boolean catalog
) {
    public CatalogGenreDefinition {
        code = safe(code);
        description = safe(description);
        category = safe(category);
        translatedCode = safe(translatedCode);
    }

    private static String safe(String value) {
        return value == null ? "" : value.trim();
    }
}
