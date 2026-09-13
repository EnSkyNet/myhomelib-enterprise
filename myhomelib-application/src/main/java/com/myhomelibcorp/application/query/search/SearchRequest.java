package com.myhomelibcorp.application.query.search;

import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import com.myhomelibcorp.domain.model.search.SmartCollectionSpec;

import java.time.LocalDate;
import java.util.List;

/**
 * Full-text/Lucene search request. Classic searches use Lucene relevance order;
 * smart collections may request deterministic Lucene DocValues ordering.
 * Catalogue table sorting outside smart collections remains a BookQuery/SQL concern.
 */
public record SearchRequest(
        String text,
        AuthorId authorId,
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
        SmartCollectionSpec smartCollectionSpec,
        List<CustomFieldSearchFilter> customFieldFilters,
        int limit,
        int offset,
        SearchMode mode,
        boolean trackTotalHits
) {
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String text;
        private AuthorId authorId;
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
        private SmartCollectionSpec smartCollectionSpec;
        private List<CustomFieldSearchFilter> customFieldFilters = List.of();
        private int limit = 100;
        private int offset = 0;
        private SearchMode mode = SearchMode.PHRASE;
        private boolean trackTotalHits = true;

        public Builder text(String text) { this.text = text; return this; }
        public Builder authorId(AuthorId authorId) { this.authorId = authorId; return this; }
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
        public Builder smartCollectionSpec(SmartCollectionSpec smartCollectionSpec) { this.smartCollectionSpec = smartCollectionSpec; return this; }
        public Builder customFieldFilters(List<CustomFieldSearchFilter> customFieldFilters) { this.customFieldFilters = customFieldFilters == null ? List.of() : List.copyOf(customFieldFilters); return this; }
        public Builder limit(int limit) { this.limit = limit; return this; }
        public Builder offset(int offset) { this.offset = offset; return this; }
        public Builder mode(SearchMode mode) { this.mode = mode; return this; }
        public Builder trackTotalHits(boolean trackTotalHits) { this.trackTotalHits = trackTotalHits; return this; }

        public SearchRequest build() {
            return new SearchRequest(
                    text, authorId, genreId, language,
                    ratingFrom, ratingTo, yearFrom, yearTo, addedFrom, addedTo, localOnly, filterSpec, smartCollectionSpec, customFieldFilters,
                    limit, offset, mode, trackTotalHits
            );
        }
    }
}
