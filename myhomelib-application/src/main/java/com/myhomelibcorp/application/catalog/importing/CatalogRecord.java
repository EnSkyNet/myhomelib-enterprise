package com.myhomelibcorp.application.catalog.importing;

import com.myhomelibcorp.application.imports.diagnostics.ImportIssue;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Source-neutral catalog record. Application import logic must depend on this model,
 * not on INPX-specific fields.
 */
public record CatalogRecord(
        String sourceBookId,
        String title,
        List<CatalogPerson> authors,
        String series,
        Double sequence,
        List<String> genres,
        String language,
        String fileFormat,
        String fileName,
        String archive,
        String archiveEntry,
        Long size,
        boolean deleted,
        String isbn,
        String publisher,
        Integer publicationYear,
        String publicationCity,
        List<CatalogPerson> translators,
        String annotation,
        List<String> keywords,
        Double rating,
        LocalDateTime sourceUpdatedAt,
        Map<String, String> sourceMetadata,
        List<CatalogArtifact> artifacts,
        List<ExternalIdentity> externalIdentities,
        List<ImportIssue> issues
) {
    public CatalogRecord {
        sourceBookId = safe(sourceBookId);
        title = safe(title);
        authors = authors == null ? List.of() : List.copyOf(authors);
        series = safe(series);
        sequence = finiteOrNull(sequence);
        genres = genres == null ? List.of() : List.copyOf(genres);
        language = safe(language);
        fileFormat = safe(fileFormat);
        fileName = safe(fileName);
        archive = safe(archive);
        archiveEntry = safe(archiveEntry);
        isbn = safe(isbn);
        publisher = safe(publisher);
        publicationCity = safe(publicationCity);
        translators = translators == null ? List.of() : List.copyOf(translators);
        annotation = safe(annotation);
        keywords = keywords == null ? List.of() : List.copyOf(keywords);
        rating = finiteOrNull(rating);
        sourceMetadata = sourceMetadata == null ? Map.of() : Map.copyOf(sourceMetadata);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
        externalIdentities = externalIdentities == null ? List.of() : List.copyOf(externalIdentities);
        issues = issues == null ? List.of() : List.copyOf(issues);
    }

    private static String safe(String value) { return value == null ? "" : value.trim(); }
    private static Double finiteOrNull(Double value) { return value == null || !Double.isFinite(value) ? null : value; }
}
