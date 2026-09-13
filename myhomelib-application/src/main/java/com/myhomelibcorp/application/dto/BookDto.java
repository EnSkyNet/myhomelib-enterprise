package com.myhomelibcorp.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;

@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class BookDto {
    private String id;
    private String title;
    private String authorsText;
    private List<AuthorDto> authors;
    private List<String> genres;
    private List<GenreDto> genreItems;
    private String series;
    private String genresText;
    private Integer sequenceNumber;
    private String language;
    private String fileName;
    private String folder;
    private String archiveEntry;
    private long fileSize;
    private String keywords;
    private String annotation;
    private int rate;
    private int progress;
    private LocalDateTime updateDate;
    private boolean deleted;
    private boolean local;
    private LocalDateTime missingSince;
    private String collectionRoot;
    private List<BookArtifactDto> artifacts;
    private String preferredArtifactId;
    private String review;
    private LocalDateTime createdAt;

    // ДОДАНІ ПОЛЯ
    private Integer year;
    private String publisher;
    private String isbn;
    private String translators;
    private String city;
    private String sourceUrl;
    private String libId;
    private int libraryRate;

    /**
     * Безпечний гетер для списку авторів.
     * Ніколи не повертає null.
     */
    public List<AuthorDto> getAuthors() {
        return authors != null ? authors : Collections.emptyList();
    }

    /**
     * Безпечний гетер для списку жанрів.
     * Ніколи не повертає null.
     */
    public List<GenreDto> getGenreItems() {
        return genreItems != null ? genreItems : Collections.emptyList();
    }

    /**
     * Безпечний гетер для списку жанрів (рядки).
     * Ніколи не повертає null.
     */
    public List<String> getGenres() {
        return genres != null ? genres : Collections.emptyList();
    }

    public List<BookArtifactDto> getArtifacts() {
        return artifacts != null ? Collections.unmodifiableList(artifacts) : Collections.emptyList();
    }

    public BookArtifactDto getPreferredArtifact() {
        if (artifacts == null || artifacts.isEmpty()) return null;
        if (preferredArtifactId != null && !preferredArtifactId.isBlank()) {
            for (BookArtifactDto artifact : artifacts) {
                if (artifact != null && preferredArtifactId.equals(artifact.getId())) return artifact;
            }
        }
        for (BookArtifactDto artifact : artifacts) {
            if (artifact != null && artifact.isLocal() && "AVAILABLE".equalsIgnoreCase(artifact.getState())) return artifact;
        }
        return artifacts.stream().filter(java.util.Objects::nonNull).findFirst().orElse(null);
    }

    /** Returns a DTO whose legacy storage fields point at the selected representation. */
    public BookDto projectArtifact(BookArtifactDto artifact) {
        if (artifact == null) throw new IllegalArgumentException("Artifact is required");
        return toBuilder()
                .fileName(artifact.getFileName())
                .folder(artifact.getFolder())
                .collectionRoot(artifact.getCollectionRoot())
                .archiveEntry(artifact.getArchiveEntry())
                .fileSize(artifact.getFileSize())
                .local(artifact.isLocal())
                .missingSince(artifact.isLocal() ? null : missingSince)
                .build();
    }

    public String getFileSizeFormatted() {
        if (fileSize <= 0) return "";
        if (fileSize < 1024) return fileSize + " B";
        if (fileSize < 1024 * 1024) return String.format("%.1f КБ", fileSize / 1024.0);
        if (fileSize < 1024 * 1024 * 1024) return String.format("%.1f МБ", fileSize / (1024.0 * 1024.0));
        return String.format("%.1f ГБ", fileSize / (1024.0 * 1024.0 * 1024.0));
    }

    public String getRateStars() {
        if (rate <= 0) return "";
        return "⭐".repeat(Math.min(rate, 5));
    }

    public String getUpdateDateFormatted() {
        if (updateDate == null) return "";
        return updateDate.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
    }

    public String getProgressFormatted() {
        return progress + "%";
    }

    public String getCreatedAtFormatted() {
        if (createdAt == null) return "";
        return createdAt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
    }

    public String getLocalStatus() {
        if (local) return "Локальна";
        return missingSince != null ? "Файл відсутній" : "Хмарна";
    }
}