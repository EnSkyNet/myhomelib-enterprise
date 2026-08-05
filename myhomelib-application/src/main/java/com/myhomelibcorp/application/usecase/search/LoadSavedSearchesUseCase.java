package com.myhomelibcorp.application.usecase.search;

import com.myhomelibcorp.application.port.out.repository.SavedSearchRepository;
import com.myhomelibcorp.domain.model.search.SavedSearch;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class LoadSavedSearchesUseCase {

    private final SavedSearchRepository savedSearchRepository;

    public List<SavedSearch> execute() {
        return savedSearchRepository.findAll();
    }

    public List<SavedSearch> executeRecent(int limit) {
        return savedSearchRepository.findRecent(limit);
    }

    public List<SavedSearch> executeMostUsed(int limit) {
        return savedSearchRepository.findMostUsed(limit);
    }
}