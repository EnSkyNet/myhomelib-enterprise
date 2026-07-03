package com.myhomelibcorp.application.port.out.search;

import com.myhomelibcorp.application.query.search.SearchMode;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.application.query.search.SearchResult;

import java.util.List;

public interface SearchQueryService {

    /**
     * @deprecated Використовуйте {@link #search(SearchRequest)}
     */
    @Deprecated
    List<String> searchBookIds(String query, int limit);

    /**
     * Новий структурований пошук.
     */
    SearchResult search(SearchRequest request);

    default SearchResult autocomplete(String prefix, int limit) {
        SearchRequest request = SearchRequest.builder()
                .text(prefix)
                .limit(limit)
                .mode(SearchMode.PREFIX)
                .build();
        return search(request);
    }

    default SearchResult suggest(String text, int limit) {
        SearchRequest request = SearchRequest.builder()
                .text(text)
                .limit(limit)
                .mode(SearchMode.FUZZY)
                .build();
        return search(request);
    }
}