package com.myhomelibcorp.application.query;

import com.myhomelibcorp.domain.model.valueobject.*;

public record BookQuery(
        AuthorId authorId,
        SeriesId seriesId,
        GenreId genreId,
        GroupId groupId,
        String text,
        LanguageCode language,
        BookFormat format,
        Pagination pagination,
        SortBy sortBy,
        SortDirection direction,
        boolean onlyRead,
        boolean onlyFavorites,
        boolean withoutSeries,
        boolean withCover
) {
    public BookQuery {
        pagination = pagination != null ? pagination : Pagination.defaultPagination();
        sortBy = sortBy != null ? sortBy : SortBy.TITLE;
        direction = direction != null ? direction : SortDirection.ASC;
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
        private LanguageCode language;
        private BookFormat format;
        private Pagination pagination;
        private SortBy sortBy;
        private SortDirection direction;
        private boolean onlyRead;
        private boolean onlyFavorites;
        private boolean withoutSeries;
        private boolean withCover;

        public Builder authorId(AuthorId authorId) { this.authorId = authorId; return this; }
        public Builder seriesId(SeriesId seriesId) { this.seriesId = seriesId; return this; }
        public Builder genreId(GenreId genreId) { this.genreId = genreId; return this; }
        public Builder groupId(GroupId groupId) { this.groupId = groupId; return this; }
        public Builder text(String text) { this.text = text; return this; }
        public Builder language(LanguageCode language) { this.language = language; return this; }
        public Builder format(BookFormat format) { this.format = format; return this; }
        public Builder pagination(Pagination pagination) { this.pagination = pagination; return this; }
        public Builder sortBy(SortBy sortBy) { this.sortBy = sortBy; return this; }
        public Builder direction(SortDirection direction) { this.direction = direction; return this; }
        public Builder onlyRead(boolean onlyRead) { this.onlyRead = onlyRead; return this; }
        public Builder onlyFavorites(boolean onlyFavorites) { this.onlyFavorites = onlyFavorites; return this; }
        public Builder withoutSeries(boolean withoutSeries) { this.withoutSeries = withoutSeries; return this; }
        public Builder withCover(boolean withCover) { this.withCover = withCover; return this; }

        public BookQuery build() {
            return new BookQuery(
                    authorId,
                    seriesId,
                    genreId,
                    groupId,
                    text,
                    language,
                    format,
                    pagination,
                    sortBy,
                    direction,
                    onlyRead,
                    onlyFavorites,
                    withoutSeries,
                    withCover
            );
        }
    }
}