package com.myhomelibcorp.application.usecase.search;

import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.port.out.repository.SavedSearchRepository;
import com.myhomelibcorp.application.query.search.SearchRequest;
public class BuildSmartCollectionSearchRequestUseCase {
    private final SavedSearchRepository repository;
    private final SavedSearchOperationExecutor operations;

    public BuildSmartCollectionSearchRequestUseCase(SavedSearchRepository repository) {
        this(repository, null);
    }

    public BuildSmartCollectionSearchRequestUseCase(SavedSearchRepository repository, SavedSearchOperationExecutor operations) {
        this.repository = repository;
        this.operations = operations;
    }

    public SearchRequest execute(String id, int limit, int offset) {
        if (operations == null) return executeLocked(id, limit, offset);
        return operations.query(() -> executeLocked(id, limit, offset));
    }

    private SearchRequest executeLocked(String id, int limit, int offset) {
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
