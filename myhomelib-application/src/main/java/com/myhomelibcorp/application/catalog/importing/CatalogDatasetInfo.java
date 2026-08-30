package com.myhomelibcorp.application.catalog.importing;

import java.util.Map;

public record CatalogDatasetInfo(
        String schema,
        String recordSchema,
        String datasetId,
        String library,
        Long declaredRecords,
        Map<String, String> metadata
) {
    public CatalogDatasetInfo {
        schema = schema == null ? "" : schema;
        recordSchema = recordSchema == null ? "" : recordSchema;
        datasetId = datasetId == null ? "" : datasetId;
        library = library == null ? "" : library;
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
