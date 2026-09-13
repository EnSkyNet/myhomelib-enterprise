package com.myhomelibcorp.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Collections;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookArtifactDto {
    private String id;
    private String sourceId;
    private String name;
    private String mediaType;
    private String format;
    private String fileName;
    private String folder;
    private String collectionRoot;
    private String archiveEntry;
    private long fileSize;
    private String sha256;
    private String contentFingerprint;
    private boolean remote;
    private boolean local;
    private String state;
    private Map<String, String> metadata;

    public Map<String, String> getMetadata() {
        return metadata != null ? Collections.unmodifiableMap(metadata) : Map.of();
    }

    public String getDisplayName() {
        String label = format == null || format.isBlank() ? "FILE" : format.toUpperCase(java.util.Locale.ROOT);
        String source = archiveEntry != null && !archiveEntry.isBlank() ? archiveEntry : fileName;
        return source == null || source.isBlank() ? label : label + " — " + source;
    }
}
