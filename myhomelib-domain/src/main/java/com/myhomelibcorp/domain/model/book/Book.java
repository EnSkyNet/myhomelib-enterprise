package com.myhomelibcorp.domain.model.book;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.*;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

@Getter
public class Book {
    private final BookId id;
    private final String title;
    private final List<Author> authors;
    private final List<Genre> genres;
    private final String series;
    private final Integer sequenceNumber;
    private final BookMetadata metadata;
    private final BookFile file;
    private final List<BookArtifact> artifacts;
    private final String preferredArtifactId;
    private final Cover cover;
    private final LocalDateTime updateDate;
    private final LocalDateTime createdAt;
    private final boolean deleted;
    private final boolean local;
    private final LocalDateTime missingSince;

    private Book(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "BookId cannot be null");
        this.title = Objects.requireNonNull(builder.title, "Title cannot be null");
        if (this.title.isBlank()) {
            throw new IllegalArgumentException("Book title cannot be blank");
        }
        this.authors = new ArrayList<>(Objects.requireNonNullElse(builder.authors, List.of()));
        this.genres = new ArrayList<>(Objects.requireNonNullElse(builder.genres, List.of()));
        this.metadata = Objects.requireNonNull(builder.metadata, "BookMetadata cannot be null");
        this.file = Objects.requireNonNull(builder.file, "BookFile cannot be null");
        this.artifacts = new ArrayList<>(Objects.requireNonNullElse(builder.artifacts, List.of()));
        this.preferredArtifactId = normalizePreferredArtifactId(builder.preferredArtifactId, this.artifacts);
        this.cover = Objects.requireNonNullElse(builder.cover, Cover.empty());
        this.series = builder.series;
        this.sequenceNumber = builder.sequenceNumber;
        // Preserve the semantic difference between "record creation time" and an unknown
        // source/catalog update date. Legacy rows are allowed to have update_date = NULL;
        // inventing createdAt (or now) as updateDate corrupts exported metadata and sync logic.
        this.createdAt = builder.createdAt != null
                ? builder.createdAt
                : (builder.updateDate != null ? builder.updateDate : LocalDateTime.now());
        this.updateDate = builder.updateDate;
        this.deleted = builder.deleted;
        this.local = builder.local;
        this.missingSince = builder.missingSince;
    }

    /**
     * Exposes relationship collections as immutable views. Repository mappers may still populate the
     * aggregate through addAuthor/addGenre, but callers cannot bypass domain mutation methods.
     */
    public List<Author> getAuthors() { return Collections.unmodifiableList(authors); }
    public List<Genre> getGenres() { return Collections.unmodifiableList(genres); }
    public List<BookArtifact> getArtifacts() { return Collections.unmodifiableList(artifacts); }

    public String getPreferredArtifactId() { return preferredArtifactId; }

    public java.util.Optional<BookArtifact> getPreferredArtifact() {
        if (artifacts.isEmpty()) return java.util.Optional.empty();
        if (preferredArtifactId != null && !preferredArtifactId.isBlank()) {
            java.util.Optional<BookArtifact> explicit = artifacts.stream()
                    .filter(a -> preferredArtifactId.equals(a.getId()))
                    .findFirst();
            if (explicit.isPresent()) return explicit;
        }
        return artifacts.stream().filter(BookArtifact::isAvailable).findFirst()
                .or(() -> artifacts.stream().filter(BookArtifact::isLocal).findFirst())
                .or(() -> artifacts.stream().findFirst());
    }

    // === ДЕЛЕГУЮЧІ МЕТОДИ ДЛЯ ЗРУЧНОСТІ (не порушують інкапсуляцію) ===
    public String getFileName() { return file != null ? file.getFileName() : ""; }
    public String getFolder() { return file != null ? file.getFolder() : ""; }
    public String getArchiveEntry() { return file != null ? file.getArchiveEntry() : ""; }
    public long getFileSize() { return file != null ? file.getFileSize() : 0; }
    public String getCollectionRoot() {return file != null ? file.getCollectionRoot() : "";    }
    public String getAnnotation() { return metadata != null ? metadata.getAnnotation() : ""; }
    public String getKeywords() { return metadata != null ? metadata.getKeywords() : ""; }
    public LanguageCode getLanguage() { return metadata != null ? metadata.getLanguage() : LanguageCode.of("und"); }
    public Isbn getIsbn() { return metadata != null ? metadata.getIsbn() : null; }
    public String getReview() { return metadata != null ? metadata.getReview() : ""; }
    public Integer getYear() { return metadata != null ? metadata.getYear() : null; }
    public String getPublisher() { return metadata != null ? metadata.getPublisher() : ""; }
    public String getLibId() { return metadata != null ? metadata.getLibId() : ""; }
    public int getLibraryRate() { return metadata != null ? metadata.getLibraryRate() : 0; }
    public String getTranslators() { return metadata != null ? metadata.getTranslators() : ""; }
    public String getCity() { return metadata != null ? metadata.getCity() : ""; }
    public String getSourceUrl() { return metadata != null ? metadata.getSourceUrl() : ""; }
    public int getRate() { return metadata != null ? metadata.getRate() : 0; }
    public int getProgress() { return metadata != null ? metadata.getProgress() : 0; }

    // === МЕТОДИ ДЛЯ ДОДАВАННЯ (потрібні для наповнення з БД) ===
    public void addAuthor(Author author) {
        if (author != null && !this.authors.contains(author)) {
            this.authors.add(author);
        }
    }

    public void addGenre(Genre genre) {
        if (genre != null && !this.genres.contains(genre)) {
            this.genres.add(genre);
        }
    }

    // === ІНШІ ЗРУЧНІ МЕТОДИ ===
    public String authorsText() {
        return authors.stream()
                .map(Author::getFullName)
                .filter(name -> name != null && !name.isBlank())
                .filter(name -> !isLegacyUnknownAuthorPlaceholder(name))
                .collect(java.util.stream.Collectors.joining(", "));
    }

    /**
     * Older importers used a localized fake author as a sentinel for missing metadata.
     * Keep the domain representation language-neutral while remaining compatible with
     * collections that may still contain those legacy rows.
     */
    public static boolean isLegacyUnknownAuthorPlaceholder(String value) {
        if (value == null) return false;
        String normalized = value.strip();
        return normalized.equalsIgnoreCase("Невідомий автор")
                || normalized.equalsIgnoreCase("Без автора");
    }

    public String genresText() {
        return genres.stream()
                .map(Genre::getName)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    public boolean hasArchiveEntry() {
        return file != null && file.hasArchiveEntry();
    }

    // === ПОВЕДІНКОВІ МЕТОДИ ===
    public Book changeTitle(String newTitle) {
        if (newTitle == null || newTitle.isBlank()) {
            throw new IllegalArgumentException("New title cannot be empty");
        }
        return toBuilder()
                .title(newTitle)
                .build();
    }

    public Book changeMetadata(BookMetadata newMetadata) {
        if (newMetadata == null) {
            throw new IllegalArgumentException("Metadata cannot be null");
        }
        return toBuilder()
                .metadata(newMetadata)
                .build();
    }

    public Book changeFile(BookFile newFile) {
        if (newFile == null) {
            throw new IllegalArgumentException("File cannot be null");
        }
        return toBuilder()
                .file(newFile)
                .build();
    }

    /** Returns a copy with changed local-file availability while preserving metadata and user state. */
    public Book withLocal(boolean local) {
        return withLocalAvailability(local, local ? null : this.missingSince);
    }

    public Book withLocalAvailability(boolean local, LocalDateTime missingSince) {
        if (this.local == local && Objects.equals(this.missingSince, missingSince)) return this;
        return toBuilder()
                .local(local)
                .missingSince(missingSince)
                .build();
    }


    /** Returns a copy with a complete artifact set and an optional preferred artifact. */
    public Book withArtifacts(List<BookArtifact> newArtifacts, String newPreferredArtifactId) {
        List<BookArtifact> safe = new ArrayList<>(Objects.requireNonNullElse(newArtifacts, List.of()));
        String preferred = resolvePreferredArtifactId(newPreferredArtifactId, safe, this.file);
        BookFile operationalFile = safe.stream()
                .filter(a -> preferred != null && preferred.equals(a.getId()))
                .findFirst()
                .or(() -> safe.stream().filter(BookArtifact::isAvailable).filter(a -> !isDerivedConversion(a)).findFirst())
                .or(() -> safe.stream().filter(BookArtifact::isAvailable).findFirst())
                .or(() -> safe.stream().findFirst())
                .map(BookArtifact::getFile)
                .orElse(this.file);
        return toBuilder()
                .file(operationalFile)
                .artifacts(safe)
                .preferredArtifactId(preferred)
                .build();
    }

    public Book addArtifact(BookArtifact artifact, boolean makePreferred) {
        Objects.requireNonNull(artifact, "Artifact cannot be null");
        List<BookArtifact> updated = new ArrayList<>(artifacts);
        updated.removeIf(existing -> existing.getId().equals(artifact.getId()));
        updated.add(artifact);
        return withArtifacts(updated, makePreferred ? artifact.getId() : preferredArtifactId);
    }

    public Book removeArtifact(String artifactId) {
        if (artifactId == null || artifactId.isBlank()) return this;
        List<BookArtifact> updated = artifacts.stream()
                .filter(a -> !artifactId.equals(a.getId()))
                .toList();
        if (updated.size() == artifacts.size()) return this;
        String preferred = artifactId.equals(preferredArtifactId) ? null : preferredArtifactId;
        return withArtifacts(updated, preferred);
    }

    public Book selectPreferredArtifact(String artifactId) {
        if (artifactId == null || artifactId.isBlank()) {
            throw new IllegalArgumentException("Preferred artifact id cannot be blank");
        }
        boolean exists = artifacts.stream().anyMatch(a -> artifactId.equals(a.getId()));
        if (!exists) throw new IllegalArgumentException("Artifact does not belong to book: " + artifactId);
        return withArtifacts(artifacts, artifactId);
    }

    private static String normalizePreferredArtifactId(String value, List<BookArtifact> artifacts) {
        if (artifacts == null || artifacts.isEmpty()) return null;
        if (value != null && !value.isBlank() && artifacts.stream().anyMatch(a -> value.equals(a.getId()))) {
            return value;
        }
        return artifacts.stream().filter(BookArtifact::isAvailable).filter(a -> !isDerivedConversion(a)).findFirst()
                .or(() -> artifacts.stream().filter(BookArtifact::isLocal).filter(a -> !isDerivedConversion(a)).findFirst())
                .or(() -> artifacts.stream().filter(BookArtifact::isAvailable).findFirst())
                .or(() -> artifacts.stream().filter(BookArtifact::isLocal).findFirst())
                .or(() -> artifacts.stream().findFirst())
                .map(BookArtifact::getId)
                .orElse(null);
    }

    private static String resolvePreferredArtifactId(String explicit, List<BookArtifact> artifacts, BookFile legacyProjection) {
        if (artifacts == null || artifacts.isEmpty()) return null;
        if (explicit != null && !explicit.isBlank() && artifacts.stream().anyMatch(a -> explicit.equals(a.getId()))) {
            return explicit;
        }
        if (legacyProjection != null) {
            java.util.Optional<BookArtifact> exactOriginalProjection = artifacts.stream()
                    .filter(a -> sameStorageProjection(a.getFile(), legacyProjection))
                    .filter(a -> !isDerivedConversion(a))
                    .findFirst();
            if (exactOriginalProjection.isPresent()) return exactOriginalProjection.get().getId();
        }
        // Never infer a generated conversion as the primary representation while any
        // source/imported artifact still exists. A conversion becomes preferred only through
        // an explicit persisted preference selected by the user.
        return normalizePreferredArtifactId(null, artifacts);
    }

    private static boolean sameStorageProjection(BookFile left, BookFile right) {
        if (left == null || right == null) return false;
        return Objects.equals(clean(left.getFileName()), clean(right.getFileName()))
                && Objects.equals(clean(left.getFolder()), clean(right.getFolder()))
                && Objects.equals(clean(left.getArchiveEntry()), clean(right.getArchiveEntry()))
                && Objects.equals(clean(left.getCollectionRoot()), clean(right.getCollectionRoot()));
    }

    private static boolean isDerivedConversion(BookArtifact artifact) {
        if (artifact == null) return false;
        String sourceId = artifact.getSourceId();
        if (sourceId != null && sourceId.regionMatches(true, 0, "conversion:", 0, "conversion:".length())) return true;
        var metadata = artifact.getMetadata();
        return metadata != null && (metadata.containsKey("conversion.sourceFormat")
                || metadata.containsKey("conversion.provider")
                || metadata.containsKey("conversion.createdAt"));
    }

    private static String clean(String value) { return value == null ? "" : value; }

    // === BUILDER ===
    /** Complete copy for focused edits; relationship lists are detached at snapshot time. */
    public Builder toBuilder() {
        return builder()
                .id(id).title(title)
                .authors(new ArrayList<>(authors)).genres(new ArrayList<>(genres))
                .series(series).sequenceNumber(sequenceNumber).metadata(metadata).file(file)
                .artifacts(new ArrayList<>(artifacts)).preferredArtifactId(preferredArtifactId)
                .cover(cover).updateDate(updateDate).createdAt(createdAt)
                .deleted(deleted).local(local).missingSince(missingSince);
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private BookId id;
        private String title;
        private List<Author> authors = new ArrayList<>();
        private List<Genre> genres = new ArrayList<>();
        private String series;
        private Integer sequenceNumber;
        private BookMetadata metadata;
        private BookFile file;
        private List<BookArtifact> artifacts = new ArrayList<>();
        private String preferredArtifactId;
        private Cover cover = Cover.empty();
        private LocalDateTime updateDate;
        private LocalDateTime createdAt;
        private boolean deleted;
        private boolean local;
        private LocalDateTime missingSince;

        public Builder id(BookId id) { this.id = id; return this; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder authors(List<Author> authors) { this.authors = authors; return this; }
        public Builder genres(List<Genre> genres) { this.genres = genres; return this; }
        public Builder series(String series) { this.series = series; return this; }
        public Builder sequenceNumber(Integer sequenceNumber) { this.sequenceNumber = sequenceNumber; return this; }
        public Builder metadata(BookMetadata metadata) { this.metadata = metadata; return this; }
        public Builder file(BookFile file) { this.file = file; return this; }
        public Builder artifacts(List<BookArtifact> artifacts) { this.artifacts = artifacts; return this; }
        public Builder preferredArtifactId(String preferredArtifactId) { this.preferredArtifactId = preferredArtifactId; return this; }
        public Builder cover(Cover cover) { this.cover = cover; return this; }
        public Builder updateDate(LocalDateTime updateDate) { this.updateDate = updateDate; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder deleted(boolean deleted) { this.deleted = deleted; return this; }
        public Builder local(boolean local) { this.local = local; return this; }
        public Builder missingSince(LocalDateTime missingSince) { this.missingSince = missingSince; return this; }

        // Зручні методи для створення VO всередині (можна використовувати, але краще передавати готові)
        public Builder metadataFrom(BookMetadata metadata) { this.metadata = metadata; return this; }
        public Builder fileFrom(BookFile file) { this.file = file; return this; }
        public Builder coverFrom(Cover cover) { this.cover = cover; return this; }

        public Book build() {
            if (id == null) id = BookId.generate();
            if (metadata == null) metadata = BookMetadata.empty();
            if (file == null) file = BookFile.empty();
            return new Book(this);
        }
    }

}
