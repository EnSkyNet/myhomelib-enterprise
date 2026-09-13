package com.myhomelibcorp.application.usecase.search;

import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.port.out.repository.SavedSearchRepository;
import com.myhomelibcorp.application.query.search.SearchRequest;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class BuildSmartCollectionSearchRequestUseCase {
    private final SavedSearchRepository repository;

    public SearchRequest execute(String id, int limit, int offset) {
        var saved = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Smart collection not found: " + id));
        if (!saved.isSmartCollection()) {
            throw new IllegalArgumentException("Saved search is not a smart collection: " + id);
        }
        return SearchRequest.builder()
                .text("")
                .filterSpec(BookFilterSpec.empty())
                .smartCollectionSpec(saved.getSmartCollection())
                .limit(limit)
                .offset(offset)
                .build();
    }
}
