package com.myhomelibcorp.domain.model.book;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.*;
import lombok.Getter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Getter
public class Book {
    private final BookId id;
    private String title;
    private List<Author> authors;
    private List<Genre> genres;
    private String series;
    private Integer sequenceNumber;
    private BookMetadata metadata;
    private BookFile file;
    private Cover cover;
    private LocalDateTime updateDate;
    private LocalDateTime createdAt;
    private boolean deleted;
    private boolean local;

    private Book(Builder builder) {
        this.id = builder.id != null ? builder.id : BookId.generate();
        this.title = builder.title;
        this.authors = builder.authors != null ? builder.authors : new ArrayList<>();
        this.genres = builder.genres != null ? builder.genres : new ArrayList<>();
        this.series = builder.series;
        this.sequenceNumber = builder.sequenceNumber;

        // Створюємо BookMetadata, якщо його не було встановлено явно
        if (builder.metadata != null) {
            this.metadata = builder.metadata;
        } else {
            this.metadata = BookMetadata.builder()
                    .annotation(builder.annotation)
                    .keywords(builder.keywords)
                    .language(builder.language != null ? builder.language : LanguageCode.of("uk"))
                    .isbn(builder.isbn)
                    .review(builder.review)
                    .rate(builder.rate)
                    .progress(builder.progress)
                    .build();
        }

        // Створюємо BookFile, якщо його не було встановлено явно
        if (builder.file != null) {
            this.file = builder.file;
        } else {
            this.file = new BookFile(
                    builder.fileName,
                    builder.folder,
                    builder.archiveEntry,
                    builder.fileSize,
                    builder.collectionRoot
            );
        }

        this.cover = builder.cover != null ? builder.cover : Cover.empty();
        this.updateDate = builder.updateDate != null ? builder.updateDate : LocalDateTime.now();
        this.createdAt = builder.createdAt != null ? builder.createdAt : LocalDateTime.now();
        this.deleted = builder.deleted;
        this.local = builder.local;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private BookId id;
        private String title;
        private List<Author> authors;
        private List<Genre> genres;
        private String series;
        private Integer sequenceNumber;
        private BookMetadata metadata;
        private BookFile file;
        private Cover cover;
        private LocalDateTime updateDate;
        private LocalDateTime createdAt;
        private boolean deleted;
        private boolean local;

        // Поля для зручного створення BookMetadata
        private String annotation;
        private String keywords;
        private LanguageCode language;
        private Isbn isbn;
        private String review;
        private int rate;
        private int progress;

        // Поля для зручного створення BookFile
        private String fileName;
        private String folder;
        private String archiveEntry;
        private long fileSize;
        private String collectionRoot;

        // === Основні методи ===
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

        // === Зручні методи для BookMetadata ===
        public Builder language(LanguageCode language) {
            this.language = language;
            return this;
        }

        public Builder language(String languageCode) {
            this.language = LanguageCode.of(languageCode);
            return this;
        }

        public Builder isbn(String isbn) {
            this.isbn = isbn != null ? Isbn.of(isbn) : null;
            return this;
        }

        public Builder annotation(String annotation) {
            this.annotation = annotation;
            return this;
        }

        public Builder keywords(String keywords) {
            this.keywords = keywords;
            return this;
        }

        public Builder review(String review) {
            this.review = review;
            return this;
        }

        public Builder rate(int rate) {
            this.rate = rate;
            return this;
        }

        public Builder progress(int progress) {
            this.progress = progress;
            return this;
        }

        // === Зручні методи для BookFile ===
        public Builder fileName(String fileName) {
            this.fileName = fileName;
            return this;
        }

        public Builder folder(String folder) {
            this.folder = folder;
            return this;
        }

        public Builder archiveEntry(String archiveEntry) {
            this.archiveEntry = archiveEntry;
            return this;
        }

        public Builder fileSize(long fileSize) {
            this.fileSize = fileSize;
            return this;
        }

        public Builder collectionRoot(String collectionRoot) {
            this.collectionRoot = collectionRoot;
            return this;
        }

        public Book build() {
            return new Book(this);
        }
    }

    // === Зручні методи доступу до полів ===
    public String getFileName() {
        return file != null ? file.getDisplayName() : "";
    }

    public String getFolder() {
        return file != null ? file.getFolder() : "";
    }

    public String getArchiveEntry() {
        return file != null ? file.getArchiveEntryName() : "";
    }

    public long getFileSize() {
        return file != null ? file.getFileSize() : 0;
    }

    public String getCollectionRoot() {
        return file != null ? file.getCollectionRoot() : "";
    }

    public String getAnnotation() {
        return metadata != null ? metadata.getAnnotation() : "";
    }

    public String getKeywords() {
        return metadata != null ? metadata.getKeywords() : "";
    }

    public LanguageCode getLanguage() {
        return metadata != null ? metadata.getLanguage() : LanguageCode.of("uk");
    }

    public Isbn getIsbn() {
        return metadata != null ? metadata.getIsbn() : null;
    }

    public String getReview() {
        return metadata != null ? metadata.getReview() : "";
    }

    public int getRate() {
        return metadata != null ? metadata.getRate() : 0;
    }

    public int getProgress() {
        return metadata != null ? metadata.getProgress() : 0;
    }

    public boolean hasArchiveEntry() {
        return file != null && file.hasArchiveEntry();
    }

    public String authorsText() {
        if (authors == null || authors.isEmpty()) {
            return "Невідомий Автор";
        }
        return authors.stream()
                .map(Author::getFullName)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    public String genresText() {
        if (genres == null || genres.isEmpty()) {
            return "";
        }
        return genres.stream()
                .map(Genre::getName)
                .collect(java.util.stream.Collectors.joining(", "));
    }

    public void addAuthor(Author author) {
        if (this.authors == null) {
            this.authors = new ArrayList<>();
        }
        this.authors.add(author);
    }

    public void addGenre(Genre genre) {
        if (this.genres == null) {
            this.genres = new ArrayList<>();
        }
        this.genres.add(genre);
    }

    public void updateRate(int rate) {
        if (rate < 0 || rate > 5) {
            throw new IllegalArgumentException("Rate must be between 0 and 5");
        }
        this.metadata = BookMetadata.builder()
                .annotation(metadata.getAnnotation())
                .keywords(metadata.getKeywords())
                .language(metadata.getLanguage())
                .isbn(metadata.getIsbn())
                .review(metadata.getReview())
                .rate(rate)
                .progress(metadata.getProgress())
                .build();
        this.updateDate = LocalDateTime.now();
    }

    public void updateProgress(int progress) {
        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException("Progress must be between 0 and 100");
        }
        this.metadata = BookMetadata.builder()
                .annotation(metadata.getAnnotation())
                .keywords(metadata.getKeywords())
                .language(metadata.getLanguage())
                .isbn(metadata.getIsbn())
                .review(metadata.getReview())
                .rate(metadata.getRate())
                .progress(progress)
                .build();
        this.updateDate = LocalDateTime.now();
    }
}