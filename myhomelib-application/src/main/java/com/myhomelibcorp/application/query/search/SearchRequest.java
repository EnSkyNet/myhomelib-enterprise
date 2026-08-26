package com.myhomelibcorp.application.query.search;

import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.query.common.SortBy;
import com.myhomelibcorp.application.query.common.SortDirection;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;

import java.time.LocalDate;

public record SearchRequest(
        String text,
        AuthorId authorId,
        SeriesId seriesId,
        GenreId genreId,
        LanguageCode language,
        Integer ratingFrom,
        Integer ratingTo,
        Integer yearFrom,
        Integer yearTo,
        LocalDate addedFrom,
        LocalDate addedTo,
        Boolean localOnly,
        BookFilterSpec filterSpec,
        int limit,
        int offset,
        SortBy sortBy,
        SortDirection direction,
        SearchMode mode
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String text;
        private AuthorId authorId;
        private SeriesId seriesId;
        private GenreId genreId;
        private LanguageCode language;
        private Integer ratingFrom;
        private Integer ratingTo;
        private Integer yearFrom;
        private Integer yearTo;
        private LocalDate addedFrom;
        private LocalDate addedTo;
        private Boolean localOnly;
        private BookFilterSpec filterSpec;
        private int limit = 100;
        private int offset = 0;
        private SortBy sortBy = SortBy.TITLE;
        private SortDirection direction = SortDirection.ASC;
        private SearchMode mode = SearchMode.PHRASE;

        public Builder text(String text) { this.text = text; return this; }
        public Builder authorId(AuthorId authorId) { this.authorId = authorId; return this; }
        public Builder seriesId(SeriesId seriesId) { this.seriesId = seriesId; return this; }
        public Builder genreId(GenreId genreId) { this.genreId = genreId; return this; }
        public Builder language(LanguageCode language) { this.language = language; return this; }
        public Builder ratingFrom(Integer ratingFrom) { this.ratingFrom = ratingFrom; return this; }
        public Builder ratingTo(Integer ratingTo) { this.ratingTo = ratingTo; return this; }
        public Builder yearFrom(Integer yearFrom) { this.yearFrom = yearFrom; return this; }
        public Builder yearTo(Integer yearTo) { this.yearTo = yearTo; return this; }
        public Builder addedFrom(LocalDate addedFrom) { this.addedFrom = addedFrom; return this; }
        public Builder addedTo(LocalDate addedTo) { this.addedTo = addedTo; return this; }
        public Builder localOnly(Boolean localOnly) { this.localOnly = localOnly; return this; }
        public Builder filterSpec(BookFilterSpec filterSpec) { this.filterSpec = filterSpec; return this; }
        public Builder limit(int limit) { this.limit = limit; return this; }
        public Builder offset(int offset) { this.offset = offset; return this; }
        public Builder sortBy(SortBy sortBy) { this.sortBy = sortBy; return this; }
        public Builder direction(SortDirection direction) { this.direction = direction; return this; }
        public Builder mode(SearchMode mode) { this.mode = mode; return this; }

        public SearchRequest build() {
            return new SearchRequest(
                    text, authorId, seriesId, genreId, language,
                    ratingFrom, ratingTo, yearFrom, yearTo, addedFrom, addedTo, localOnly, filterSpec,
                    limit, offset, sortBy, direction, mode
            );
        }
    }
}