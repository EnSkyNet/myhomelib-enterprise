package com.myhomelibcorp.application.usecase.search;

import com.myhomelibcorp.application.dto.PinnedSmartCollectionDto;
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

    public List<PinnedSmartCollectionDto> executePinnedSmartCollections() {
        return savedSearchRepository.findAll().stream()
                .filter(SavedSearch::isSmartCollection)
                .filter(SavedSearch::isPinned)
                .sorted(java.util.Comparator.comparing(SavedSearch::getName, String.CASE_INSENSITIVE_ORDER))
                .map(saved -> new PinnedSmartCollectionDto(saved.getId(), saved.getName()))
                .toList();
    }

}