package com.myhomelibcorp.domain.model.book;

import com.myhomelibcorp.domain.model.valueobject.BookFile;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * One concrete representation of a logical book (for example EPUB, FB2 or PDF).
 * The logical {@link Book} may own multiple artifacts while legacy code continues
 * to use {@code Book.file} as the preferred operational projection.
 */
public final class BookArtifact {
    private final String id;
    private final String sourceId;
    private final String name;
    private final String mediaType;
    private final String format;
    private final BookFile file;
    private final String sha256;
    private final String contentFingerprint;
    private final boolean remote;
    private final boolean local;
    private final BookArtifactState state;
    private final Map<String, String> metadata;

    private BookArtifact(Builder builder) {
        this.id = requireText(builder.id, "Artifact id cannot be blank");
        this.sourceId = value(builder.sourceId);
        this.name = value(builder.name);
        this.mediaType = value(builder.mediaType);
        this.format = normalizeFormat(builder.format, builder.file);
        this.file = Objects.requireNonNullElse(builder.file, BookFile.empty());
        this.sha256 = value(builder.sha256);
        this.contentFingerprint = value(builder.contentFingerprint);
        this.remote = builder.remote;
        this.local = builder.local;
        this.state = builder.state != null
                ? builder.state
                : BookArtifactState.fromStorage(null, builder.local, builder.remote);
        this.metadata = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNullElse(builder.metadata, Map.of())));
    }

    public String getId() { return id; }
    public String getSourceId() { return sourceId; }
    public String getName() { return name; }
    public String getMediaType() { return mediaType; }
    public String getFormat() { return format; }
    public BookFile getFile() { return file; }
    public String getSha256() { return sha256; }
    public String getContentFingerprint() { return contentFingerprint; }
    public boolean isRemote() { return remote; }
    public boolean isLocal() { return local; }
    public BookArtifactState getState() { return state; }
    public Map<String, String> getMetadata() { return metadata; }

    public boolean isAvailable() {
        return state == BookArtifactState.AVAILABLE && local;
    }

    public String displayName() {
        String label = !format.isBlank() ? format.toUpperCase(java.util.Locale.ROOT) : "FILE";
        String fileName = file.getArchiveEntry() != null && !file.getArchiveEntry().isBlank()
                ? file.getArchiveEntry()
                : file.getFileName();
        return fileName == null || fileName.isBlank() ? label : label + " — " + fileName;
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String id;
        private String sourceId;
        private String name;
        private String mediaType;
        private String format;
        private BookFile file;
        private String sha256;
        private String contentFingerprint;
        private boolean remote;
        private boolean local;
        private BookArtifactState state;
        private Map<String, String> metadata = Map.of();

        public Builder id(String id) { this.id = id; return this; }
        public Builder sourceId(String sourceId) { this.sourceId = sourceId; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder mediaType(String mediaType) { this.mediaType = mediaType; return this; }
        public Builder format(String format) { this.format = format; return this; }
        public Builder file(BookFile file) { this.file = file; return this; }
        public Builder sha256(String sha256) { this.sha256 = sha256; return this; }
        public Builder contentFingerprint(String contentFingerprint) { this.contentFingerprint = contentFingerprint; return this; }
        public Builder remote(boolean remote) { this.remote = remote; return this; }
        public Builder local(boolean local) { this.local = local; return this; }
        public Builder state(BookArtifactState state) { this.state = state; return this; }
        public Builder metadata(Map<String, String> metadata) { this.metadata = metadata; return this; }
        public BookArtifact build() { return new BookArtifact(this); }
    }

    private static String normalizeFormat(String value, BookFile file) {
        if (value != null && !value.isBlank()) return value.trim().toLowerCase(java.util.Locale.ROOT);
        if (file == null) return "";
        String source = file.hasArchiveEntry() ? file.getArchiveEntry() : file.getFileName();
        if (source == null) return "";
        int slash = Math.max(source.lastIndexOf('/'), source.lastIndexOf('\\'));
        int dot = source.lastIndexOf('.');
        return dot > slash && dot + 1 < source.length()
                ? source.substring(dot + 1).toLowerCase(java.util.Locale.ROOT)
                : "";
    }

    private static String requireText(String value, String message) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(message);
        return value;
    }

    private static String value(String value) { return value == null ? "" : value; }
}
