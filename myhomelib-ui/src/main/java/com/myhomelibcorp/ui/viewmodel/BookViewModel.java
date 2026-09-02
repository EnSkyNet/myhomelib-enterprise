package com.myhomelibcorp.ui.viewmodel;

import javafx.beans.property.*;
import javafx.scene.image.Image;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

public class BookViewModel {

    private final StringProperty id = new SimpleStringProperty();
    private final StringProperty title = new SimpleStringProperty();
    private final StringProperty authorsText = new SimpleStringProperty();
    private final StringProperty series = new SimpleStringProperty();
    private final StringProperty genresText = new SimpleStringProperty();
    private final IntegerProperty sequenceNumber = new SimpleIntegerProperty();
    private final IntegerProperty year = new SimpleIntegerProperty();
    private final StringProperty language = new SimpleStringProperty();
    private final StringProperty fileName = new SimpleStringProperty();
    private final StringProperty folder = new SimpleStringProperty();
    private final StringProperty archiveEntry = new SimpleStringProperty();
    private final LongProperty fileSize = new SimpleLongProperty();
    private final StringProperty keywords = new SimpleStringProperty();
    private final StringProperty annotation = new SimpleStringProperty();
    private final IntegerProperty rate = new SimpleIntegerProperty();
    private final IntegerProperty progress = new SimpleIntegerProperty();
    private final ObjectProperty<LocalDateTime> updateDate = new SimpleObjectProperty<>();
    private final BooleanProperty deleted = new SimpleBooleanProperty();
    private final BooleanProperty local = new SimpleBooleanProperty();
    private final ObjectProperty<LocalDateTime> missingSince = new SimpleObjectProperty<>();
    private final StringProperty collectionRoot = new SimpleStringProperty();
    private final StringProperty review = new SimpleStringProperty();
    private final ObjectProperty<LocalDateTime> createdAt = new SimpleObjectProperty<>();
    private final ObjectProperty<Image> cover = new SimpleObjectProperty<>();

    // Властивість для вибору
    private final BooleanProperty selected = new SimpleBooleanProperty(false);
    private final BooleanProperty groupHeader = new SimpleBooleanProperty(false);

    // Форматовані властивості
    private final StringProperty fileSizeFormatted = new SimpleStringProperty();
    private final StringProperty rateStars = new SimpleStringProperty();
    private final StringProperty progressFormatted = new SimpleStringProperty();
    private final StringProperty createdAtFormatted = new SimpleStringProperty();
    private final StringProperty updateDateFormatted = new SimpleStringProperty();
    private final StringProperty localStatus = new SimpleStringProperty();

    // ===== ГЕТЕРИ ВЛАСТИВОСТЕЙ =====
    public StringProperty idProperty() { return id; }
    public StringProperty titleProperty() { return title; }
    public StringProperty authorsTextProperty() { return authorsText; }
    public StringProperty seriesProperty() { return series; }
    public StringProperty genresTextProperty() { return genresText; }
    public IntegerProperty sequenceNumberProperty() { return sequenceNumber; }
    public IntegerProperty yearProperty() { return year; }
    public StringProperty languageProperty() { return language; }
    public StringProperty fileNameProperty() { return fileName; }
    public StringProperty folderProperty() { return folder; }
    public StringProperty archiveEntryProperty() { return archiveEntry; }
    public LongProperty fileSizeProperty() { return fileSize; }
    public StringProperty keywordsProperty() { return keywords; }
    public StringProperty annotationProperty() { return annotation; }
    public IntegerProperty rateProperty() { return rate; }
    public IntegerProperty progressProperty() { return progress; }
    public ObjectProperty<LocalDateTime> updateDateProperty() { return updateDate; }
    public BooleanProperty deletedProperty() { return deleted; }
    public BooleanProperty localProperty() { return local; }
    public ObjectProperty<LocalDateTime> missingSinceProperty() { return missingSince; }
    public StringProperty collectionRootProperty() { return collectionRoot; }
    public StringProperty reviewProperty() { return review; }
    public ObjectProperty<LocalDateTime> createdAtProperty() { return createdAt; }
    public ObjectProperty<Image> coverProperty() { return cover; }

    // Властивість вибору
    public BooleanProperty selectedProperty() { return selected; }
    public BooleanProperty groupHeaderProperty() { return groupHeader; }

    public StringProperty fileSizeFormattedProperty() { return fileSizeFormatted; }
    public StringProperty rateStarsProperty() { return rateStars; }
    public StringProperty progressFormattedProperty() { return progressFormatted; }
    public StringProperty createdAtFormattedProperty() { return createdAtFormatted; }
    public StringProperty updateDateFormattedProperty() { return updateDateFormatted; }
    public StringProperty localStatusProperty() { return localStatus; }

    // ===== ГЕТЕРИ ТА СЕТЕРИ =====
    public String getId() { return id.get(); }
    public void setId(String id) { this.id.set(id); }

    public String getTitle() { return title.get(); }
    public void setTitle(String title) { this.title.set(title); }

    public String getAuthorsText() { return authorsText.get(); }
    public void setAuthorsText(String authorsText) { this.authorsText.set(authorsText); }

    public String getSeries() { return series.get(); }
    public void setSeries(String series) { this.series.set(series); }

    public String getGenresText() { return genresText.get(); }
    public void setGenresText(String genresText) { this.genresText.set(genresText); }

    public int getSequenceNumber() { return sequenceNumber.get(); }
    public void setSequenceNumber(int sequenceNumber) { this.sequenceNumber.set(sequenceNumber); }

    public int getYear() { return year.get(); }
    public void setYear(Integer year) { this.year.set(year != null ? year : 0); }

    public String getLanguage() { return language.get(); }
    public void setLanguage(String language) { this.language.set(language); }

    public String getFileName() { return fileName.get(); }
    public void setFileName(String fileName) { this.fileName.set(fileName); }

    public String getFolder() { return folder.get(); }
    public void setFolder(String folder) { this.folder.set(folder); }

    public String getArchiveEntry() { return archiveEntry.get(); }
    public void setArchiveEntry(String archiveEntry) { this.archiveEntry.set(archiveEntry); }

    public long getFileSize() { return fileSize.get(); }
    public void setFileSize(long fileSize) {
        this.fileSize.set(fileSize);
        updateFileSizeFormatted();
    }

    public String getKeywords() { return keywords.get(); }
    public void setKeywords(String keywords) { this.keywords.set(keywords); }

    public String getAnnotation() { return annotation.get(); }
    public void setAnnotation(String annotation) { this.annotation.set(annotation); }

    public int getRate() { return rate.get(); }
    public void setRate(int rate) {
        this.rate.set(rate);
        updateRateStars();
    }

    public int getProgress() { return progress.get(); }
    public void setProgress(int progress) {
        this.progress.set(progress);
        updateProgressFormatted();
    }

    public LocalDateTime getUpdateDate() { return updateDate.get(); }
    public void setUpdateDate(LocalDateTime updateDate) {
        this.updateDate.set(updateDate);
        updateUpdateDateFormatted();
    }

    public boolean isDeleted() { return deleted.get(); }
    public void setDeleted(boolean deleted) { this.deleted.set(deleted); }

    public boolean isLocal() { return local.get(); }
    public void setLocal(boolean local) {
        this.local.set(local);
        updateLocalStatus();
    }

    public LocalDateTime getMissingSince() { return missingSince.get(); }
    public void setMissingSince(LocalDateTime value) {
        missingSince.set(value);
        updateLocalStatus();
    }

    public String getCollectionRoot() { return collectionRoot.get(); }
    public void setCollectionRoot(String collectionRoot) { this.collectionRoot.set(collectionRoot); }

    public String getReview() { return review.get(); }
    public void setReview(String review) { this.review.set(review); }

    public LocalDateTime getCreatedAt() { return createdAt.get(); }
    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt.set(createdAt);
        updateCreatedAtFormatted();
    }

    public Image getCover() { return cover.get(); }
    public void setCover(Image cover) { this.cover.set(cover); }

    // Властивість вибору
    public boolean isSelected() { return selected.get(); }
    public void setSelected(boolean selected) { this.selected.set(selected); }
    public boolean isGroupHeader() { return groupHeader.get(); }
    public void setGroupHeader(boolean groupHeader) { this.groupHeader.set(groupHeader); }

    // ===== ФОРМАТОВАНІ ГЕТЕРИ =====
    public String getFileSizeFormatted() { return fileSizeFormatted.get(); }
    public String getRateStars() { return rateStars.get(); }
    public String getProgressFormatted() { return progressFormatted.get(); }
    public String getCreatedAtFormatted() { return createdAtFormatted.get(); }
    public String getUpdateDateFormatted() { return updateDateFormatted.get(); }
    public String getLocalStatus() { return localStatus.get(); }

    // ===== ОНОВЛЕННЯ ФОРМАТОВАНИХ ПОЛІВ =====
    private void updateFileSizeFormatted() {
        long size = fileSize.get();
        if (size <= 0) { fileSizeFormatted.set(""); return; }
        if (size < 1024) fileSizeFormatted.set(size + " B");
        else if (size < 1024 * 1024) fileSizeFormatted.set(String.format("%.1f КБ", size / 1024.0));
        else if (size < 1024 * 1024 * 1024) fileSizeFormatted.set(String.format("%.1f МБ", size / (1024.0 * 1024.0)));
        else fileSizeFormatted.set(String.format("%.1f ГБ", size / (1024.0 * 1024.0 * 1024.0)));
    }

    private void updateRateStars() {
        int r = rate.get();
        rateStars.set(r > 0 ? "⭐".repeat(Math.min(r, 5)) : "");
    }

    private void updateProgressFormatted() {
        progressFormatted.set(progress.get() + "%");
    }

    private void updateCreatedAtFormatted() {
        LocalDateTime date = createdAt.get();
        createdAtFormatted.set(date != null ? date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")) : "");
    }

    private void updateUpdateDateFormatted() {
        LocalDateTime date = updateDate.get();
        updateDateFormatted.set(date != null ? date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy")) : "");
    }

    private void updateLocalStatus() {
        if (local.get()) localStatus.set("Завантажено");
        else if (missingSince.get() != null) localStatus.set("Файл відсутній");
        else localStatus.set("Не завантажено");
    }

    @Override
    public String toString() {
        return getTitle();
    }
}