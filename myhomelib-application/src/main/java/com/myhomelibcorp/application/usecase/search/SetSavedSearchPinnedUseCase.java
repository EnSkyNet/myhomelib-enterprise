package com.myhomelibcorp.application.usecase.search;

import com.myhomelibcorp.application.port.out.repository.SavedSearchRepository;
import com.myhomelibcorp.domain.model.search.SavedSearch;
import lombok.RequiredArgsConstructor;

@RequiredArgsConstructor
public class SetSavedSearchPinnedUseCase {
    private final SavedSearchRepository repository;

    public SavedSearch execute(String id, boolean pinned) {
        SavedSearch search = repository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Збережений пошук не знайдено: " + id));
        return repository.save(search.withPinned(pinned));
    }
}
