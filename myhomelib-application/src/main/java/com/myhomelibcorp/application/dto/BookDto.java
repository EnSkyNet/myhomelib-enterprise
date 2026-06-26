package com.myhomelibcorp.application.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BookDto {
    private String id;
    private String title;
    private String authorsText;
    private List<String> genres;
    private String series;
    private String genresText;
    private Integer sequenceNumber;
    private String language;
    private String fileName;
    private String folder;
    private String archiveEntry;          // ← ім'я файлу всередині архіву (FBD/ZIP)
    private long fileSize;
    private String keywords;
    private String annotation;
    private int rate;
    private int progress;
    private LocalDateTime updateDate;
    private boolean deleted;
    private boolean local;
    private String review;
    private LocalDateTime createdAt;

    // Корінь колекції (автоматично заповнюється з MainViewModel)
    private String collectionRoot;

    public BookDto(String title, String authorsText, String series, String genresText, int rate, int progress) {
        this.title = title;
        this.authorsText = authorsText;
        this.series = series;
        this.genresText = genresText;
        this.rate = rate;
        this.progress = progress;
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

    public String getLocalStatus() {
        if (deleted) return "Видалена";
        return local ? "Локальна" : "Онлайн";
    }

    public String getProgressFormatted() {
        return progress + "%";
    }

    public String getCreatedAtFormatted() {
        if (createdAt == null) return "";
        return createdAt.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
    }
}