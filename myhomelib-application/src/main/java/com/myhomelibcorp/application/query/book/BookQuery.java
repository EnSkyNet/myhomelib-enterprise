package com.myhomelibcorp.application.query.book;

import com.myhomelibcorp.application.query.common.Pagination;
import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import com.myhomelibcorp.domain.model.valueobject.*;

public record BookQuery(
        // Фільтри
        AuthorId authorId,
        SeriesId seriesId,
        GenreId genreId,
        GroupId groupId,
        String text,
        String keyword,
        LanguageCode language,
        BookFormat format,
        Integer year,
        String archiveCollectionRoot,
        String archivePath,

        // Пагінація та сортування
        Pagination pagination,
        SortBy sortBy,
        SortDirection direction,

        // Додаткові фільтри
        boolean onlyRead,
        boolean onlyFavorites,
        boolean onlyRated,
        boolean onlyReviewed,
        boolean onlyInHistory,
        boolean withoutSeries,
        boolean withCover
) {
    public BookQuery {
        pagination = pagination != null ? pagination : Pagination.defaultPagination();
        sortBy = sortBy != null ? sortBy : SortBy.TITLE;
        direction = direction != null ? direction : SortDirection.ASC;
        keyword = normalizeTextFilter(keyword);
        archiveCollectionRoot = normalizePathFilter(archiveCollectionRoot);
        archivePath = normalizePathFilter(archivePath);
        if (year != null && year <= 0) {
            throw new IllegalArgumentException("year must be positive");
        }
        if (archivePath == null) {
            archiveCollectionRoot = null;
        }
    }

    private static String normalizeTextFilter(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String normalizePathFilter(String value) {
        if (value == null) return null;
        return value.isBlank() ? null : value;
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private AuthorId authorId;
        private SeriesId seriesId;
        private GenreId genreId;
        private GroupId groupId;
        private String text;
        private String keyword;
        private LanguageCode language;
        private BookFormat format;
        private Integer year;
        private String archiveCollectionRoot;
        private String archivePath;
        private Pagination pagination;
        private SortBy sortBy;
        private SortDirection direction;
        private boolean onlyRead;
        private boolean onlyFavorites;
        private boolean onlyRated;
        private boolean onlyReviewed;
        private boolean onlyInHistory;
        private boolean withoutSeries;
        private boolean withCover;

        public Builder authorId(AuthorId authorId) { this.authorId = authorId; return this; }
        public Builder seriesId(SeriesId seriesId) { this.seriesId = seriesId; return this; }
        public Builder genreId(GenreId genreId) { this.genreId = genreId; return this; }
        public Builder groupId(GroupId groupId) { this.groupId = groupId; return this; }
        public Builder text(String text) { this.text = text; return this; }
        public Builder keyword(String keyword) { this.keyword = keyword; return this; }
        public Builder language(LanguageCode language) { this.language = language; return this; }
        public Builder format(BookFormat format) { this.format = format; return this; }
        public Builder year(Integer year) { this.year = year; return this; }
        public Builder archive(String collectionRoot, String archivePath) {
            this.archiveCollectionRoot = collectionRoot;
            this.archivePath = archivePath;
            return this;
        }
        public Builder archiveCollectionRoot(String archiveCollectionRoot) { this.archiveCollectionRoot = archiveCollectionRoot; return this; }
        public Builder archivePath(String archivePath) { this.archivePath = archivePath; return this; }
        public Builder pagination(Pagination pagination) { this.pagination = pagination; return this; }
        public Builder sortBy(SortBy sortBy) { this.sortBy = sortBy; return this; }
        public Builder direction(SortDirection direction) { this.direction = direction; return this; }
        public Builder onlyRead(boolean onlyRead) { this.onlyRead = onlyRead; return this; }
        public Builder onlyFavorites(boolean onlyFavorites) { this.onlyFavorites = onlyFavorites; return this; }
        public Builder onlyRated(boolean onlyRated) { this.onlyRated = onlyRated; return this; }
        public Builder onlyReviewed(boolean onlyReviewed) { this.onlyReviewed = onlyReviewed; return this; }
        public Builder onlyInHistory(boolean onlyInHistory) { this.onlyInHistory = onlyInHistory; return this; }
        public Builder withoutSeries(boolean withoutSeries) { this.withoutSeries = withoutSeries; return this; }
        public Builder withCover(boolean withCover) { this.withCover = withCover; return this; }

        public BookQuery build() {
            return new BookQuery(
                    authorId, seriesId, genreId, groupId, text, keyword, language, format,
                    year, archiveCollectionRoot, archivePath,
                    pagination, sortBy, direction,
                    onlyRead, onlyFavorites, onlyRated, onlyReviewed, onlyInHistory, withoutSeries, withCover
            );
        }
    }
}
