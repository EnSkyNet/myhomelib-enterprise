package com.myhomelibcorp.domain.model.book;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.*;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
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
    private final Cover cover;
    private final LocalDateTime updateDate;
    private final LocalDateTime createdAt;
    private final boolean deleted;
    private final boolean local;

    private Book(Builder builder) {
        this.id = Objects.requireNonNull(builder.id, "BookId cannot be null");
        this.title = Objects.requireNonNull(builder.title, "Title cannot be null");
        if (this.title.isBlank()) {
            throw new IllegalArgumentException("Book title cannot be blank");
        }
        this.authors = Objects.requireNonNullElse(builder.authors, new ArrayList<>());
        this.genres = Objects.requireNonNullElse(builder.genres, new ArrayList<>());
        this.metadata = Objects.requireNonNull(builder.metadata, "BookMetadata cannot be null");
        this.file = Objects.requireNonNull(builder.file, "BookFile cannot be null");
        this.cover = Objects.requireNonNullElse(builder.cover, Cover.empty());
        this.series = builder.series;
        this.sequenceNumber = builder.sequenceNumber;
        this.updateDate = Objects.requireNonNullElse(builder.updateDate, LocalDateTime.now());
        this.createdAt = Objects.requireNonNullElse(builder.createdAt, LocalDateTime.now());
        this.deleted = builder.deleted;
        this.local = builder.local;
    }

    // === ДЕЛЕГУЮЧІ МЕТОДИ ДЛЯ ЗРУЧНОСТІ (не порушують інкапсуляцію) ===
    public String getFileName() { return file != null ? file.getFileName() : ""; }
    public String getFolder() { return file != null ? file.getFolder() : ""; }
    public String getArchiveEntry() { return file != null ? file.getArchiveEntry() : ""; }
    public long getFileSize() { return file != null ? file.getFileSize() : 0; }
    public String getCollectionRoot() { return file != null ? file.getCollectionRoot() : ""; }

    public String getAnnotation() { return metadata != null ? metadata.getAnnotation() : ""; }
    public String getKeywords() { return metadata != null ? metadata.getKeywords() : ""; }
    public LanguageCode getLanguage() { return metadata != null ? metadata.getLanguage() : LanguageCode.of("uk"); }
    public Isbn getIsbn() { return metadata != null ? metadata.getIsbn() : null; }
    public String getReview() { return metadata != null ? metadata.getReview() : ""; }
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
        if (authors.isEmpty()) return "Невідомий Автор";
        return authors.stream()
                .map(Author::getFullName)
                .collect(java.util.stream.Collectors.joining(", "));
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
        return builder()
                .id(this.id)
                .title(newTitle)
                .authors(this.authors)
                .genres(this.genres)
                .series(this.series)
                .sequenceNumber(this.sequenceNumber)
                .metadata(this.metadata)
                .file(this.file)
                .cover(this.cover)
                .updateDate(LocalDateTime.now())
                .createdAt(this.createdAt)
                .deleted(this.deleted)
                .local(this.local)
                .build();
    }

    public Book changeMetadata(BookMetadata newMetadata) {
        if (newMetadata == null) {
            throw new IllegalArgumentException("Metadata cannot be null");
        }
        return builder()
                .id(this.id)
                .title(this.title)
                .authors(this.authors)
                .genres(this.genres)
                .series(this.series)
                .sequenceNumber(this.sequenceNumber)
                .metadata(newMetadata)
                .file(this.file)
                .cover(this.cover)
                .updateDate(LocalDateTime.now())
                .createdAt(this.createdAt)
                .deleted(this.deleted)
                .local(this.local)
                .build();
    }

    public Book changeFile(BookFile newFile) {
        if (newFile == null) {
            throw new IllegalArgumentException("File cannot be null");
        }
        return builder()
                .id(this.id)
                .title(this.title)
                .authors(this.authors)
                .genres(this.genres)
                .series(this.series)
                .sequenceNumber(this.sequenceNumber)
                .metadata(this.metadata)
                .file(newFile)
                .cover(this.cover)
                .updateDate(LocalDateTime.now())
                .createdAt(this.createdAt)
                .deleted(this.deleted)
                .local(this.local)
                .build();
    }

    // === BUILDER ===
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
        private Cover cover = Cover.empty();
        private LocalDateTime updateDate;
        private LocalDateTime createdAt;
        private boolean deleted;
        private boolean local;

        public Builder id(BookId id) { this.id = id; return this; }
        public Builder title(String title) { this.title = title; return this; }
        public Builder authors(List<Author> authors) { this.authors = authors; return this; }
        public Builder genres(List<Genre> genres) { this.genres = genres; return this; }
        public Builder series(String series) { this.series = series; return this; }
        public Builder sequenceNumber(Integer sequenceNumber) { this.sequenceNumber = sequenceNumber; return this; }
        public Builder metadata(BookMetadata metadata) { this.metadata = metadata; return this; }
        public Builder file(BookFile file) { this.file = file; return this; }
        public Builder cover(Cover cover) { this.cover = cover; return this; }
        public Builder updateDate(LocalDateTime updateDate) { this.updateDate = updateDate; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder deleted(boolean deleted) { this.deleted = deleted; return this; }
        public Builder local(boolean local) { this.local = local; return this; }

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