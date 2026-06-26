package com.myhomelibcorp.domain.model.book;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.Isbn;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import lombok.Getter;
import lombok.Setter;

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
    private LanguageCode language;
    private String fileName;
    private String folder;
    private String archiveEntry;
    private long fileSize;
    private String keywords;
    private String annotation;
    private int rate;
    private int progress;
    private LocalDateTime updateDate;
    private Isbn isbn;
    private boolean deleted;
    private boolean local;
    private String review;
    private LocalDateTime createdAt;

    // +++ ДОДАНО +++
    @Setter
    private String collectionRoot;

    private Book(BookId id) {
        this.id = id;
        this.authors = new ArrayList<>();
        this.genres = new ArrayList<>();
        this.updateDate = LocalDateTime.now();
        this.language = LanguageCode.of("uk");
        this.createdAt = LocalDateTime.now();
    }

    public Book(String title) {
        this(BookId.generate());
        this.title = title;
    }

    public Book(String title, List<Author> authors, List<Genre> genres) {
        this(title);
        this.authors = authors != null ? authors : new ArrayList<>();
        this.genres = genres != null ? genres : new ArrayList<>();
    }

    public static Builder builder() {
        return new Builder();
    }

    public void setAuthors(List<Author> authors) {
        this.authors = authors != null ? authors : new ArrayList<>();
    }

    public void setGenres(List<Genre> genres) {
        this.genres = genres != null ? genres : new ArrayList<>();
    }

    public static class Builder {
        private Book book;

        public Builder() {
            this.book = new Book(BookId.generate());
            this.book.language = LanguageCode.of("uk");
            this.book.createdAt = LocalDateTime.now();
        }

        public Builder id(BookId id) {
            this.book = new Book(id);
            this.book.language = LanguageCode.of("uk");
            this.book.createdAt = LocalDateTime.now();
            return this;
        }

        public Builder title(String title) {
            book.title = title;
            return this;
        }

        public Builder authors(List<Author> authors) {
            book.authors = authors != null ? authors : new ArrayList<>();
            return this;
        }

        public Builder genres(List<Genre> genres) {
            book.genres = genres != null ? genres : new ArrayList<>();
            return this;
        }

        public Builder series(String series) {
            book.series = series;
            return this;
        }

        public Builder sequenceNumber(Integer sequenceNumber) {
            book.sequenceNumber = sequenceNumber;
            return this;
        }

        public Builder language(String language) {
            this.book.language = (language != null && !language.isBlank())
                    ? LanguageCode.of(language)
                    : LanguageCode.of("uk");
            return this;
        }

        public Builder language(LanguageCode language) {
            this.book.language = (language != null) ? language : LanguageCode.of("uk");
            return this;
        }

        public Builder fileName(String fileName) {
            book.fileName = fileName;
            return this;
        }

        public Builder folder(String folder) {
            book.folder = folder;
            return this;
        }

        public Builder archiveEntry(String archiveEntry) {
            book.archiveEntry = archiveEntry;
            return this;
        }

        public Builder fileSize(long fileSize) {
            book.fileSize = fileSize;
            return this;
        }

        public Builder keywords(String keywords) {
            book.keywords = keywords;
            return this;
        }

        public Builder annotation(String annotation) {
            book.annotation = annotation;
            return this;
        }

        public Builder rate(int rate) {
            book.rate = rate;
            return this;
        }

        public Builder progress(int progress) {
            book.progress = progress;
            return this;
        }

        public Builder updateDate(LocalDateTime updateDate) {
            book.updateDate = updateDate;
            return this;
        }

        public Builder isbn(String isbn) {
            book.isbn = isbn != null ? Isbn.of(isbn) : null;
            return this;
        }

        public Builder deleted(boolean deleted) {
            book.deleted = deleted;
            return this;
        }

        public Builder local(boolean local) {
            book.local = local;
            return this;
        }

        public Builder review(String review) {
            book.review = review;
            return this;
        }

        public Builder createdAt(LocalDateTime createdAt) {
            book.createdAt = createdAt;
            return this;
        }

        // +++ ДОДАНО +++
        public Builder collectionRoot(String collectionRoot) {
            book.collectionRoot = collectionRoot;
            return this;
        }

        public Book build() {
            return book;
        }
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

    public boolean hasArchiveEntry() {
        return archiveEntry != null && !archiveEntry.isBlank();
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
        this.rate = rate;
        this.updateDate = LocalDateTime.now();
    }

    public void updateProgress(int progress) {
        if (progress < 0 || progress > 100) {
            throw new IllegalArgumentException("Progress must be between 0 and 100");
        }
        this.progress = progress;
        this.updateDate = LocalDateTime.now();
    }
}