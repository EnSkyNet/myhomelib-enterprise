package com.myhomelibcorp.application.query.book;

import com.myhomelibcorp.application.query.common.PageRequest;
import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import com.myhomelibcorp.domain.model.valueobject.*;

public record PageableBookQuery(
        AuthorId authorId,
        SeriesId seriesId,
        GenreId genreId,
        GroupId groupId,
        String text,
        LanguageCode language,
        BookFormat format,
        PageRequest pageRequest,
        boolean onlyRead,
        boolean onlyFavorites,
        boolean withoutSeries,
        boolean withCover
) {
    public PageableBookQuery {
        if (pageRequest == null) {
            pageRequest = new PageRequest(0, 100);
        }
    }

    // ---- Методи для створення нових запитів з іншими параметрами ----

    public PageableBookQuery withPage(int page) {
        PageRequest newRequest = new PageRequest(page, pageRequest.getSize(),
                pageRequest.getSortBy(), pageRequest.getDirection());
        return new PageableBookQuery(
                authorId, seriesId, genreId, groupId, text, language, format,
                newRequest, onlyRead, onlyFavorites, withoutSeries, withCover
        );
    }

    public PageableBookQuery withSize(int size) {
        PageRequest newRequest = new PageRequest(pageRequest.getPage(), size,
                pageRequest.getSortBy(), pageRequest.getDirection());
        return new PageableBookQuery(
                authorId, seriesId, genreId, groupId, text, language, format,
                newRequest, onlyRead, onlyFavorites, withoutSeries, withCover
        );
    }

    public PageableBookQuery withSort(SortBy sortBy, SortDirection direction) {
        PageRequest newRequest = new PageRequest(pageRequest.getPage(), pageRequest.getSize(),
                sortBy, direction);
        return new PageableBookQuery(
                authorId, seriesId, genreId, groupId, text, language, format,
                newRequest, onlyRead, onlyFavorites, withoutSeries, withCover
        );
    }

    public PageableBookQuery withAuthor(AuthorId authorId) {
        return new PageableBookQuery(
                authorId, seriesId, genreId, groupId, text, language, format,
                pageRequest, onlyRead, onlyFavorites, withoutSeries, withCover
        );
    }

    // ---- Builder ----
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
        private PageRequest pageRequest;
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
        public Builder pageRequest(PageRequest pageRequest) { this.pageRequest = pageRequest; return this; }
        public Builder onlyRead(boolean onlyRead) { this.onlyRead = onlyRead; return this; }
        public Builder onlyFavorites(boolean onlyFavorites) { this.onlyFavorites = onlyFavorites; return this; }
        public Builder withoutSeries(boolean withoutSeries) { this.withoutSeries = withoutSeries; return this; }
        public Builder withCover(boolean withCover) { this.withCover = withCover; return this; }

        public PageableBookQuery build() {
            return new PageableBookQuery(
                    authorId, seriesId, genreId, groupId, text, language, format,
                    pageRequest != null ? pageRequest : new PageRequest(0, 100),
                    onlyRead, onlyFavorites, withoutSeries, withCover
            );
        }
    }
}