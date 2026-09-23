package com.myhomelibcorp.application.usecase.search;

import com.myhomelibcorp.application.dto.PinnedSmartCollectionDto;
import com.myhomelibcorp.application.port.out.repository.SavedSearchRepository;
import com.myhomelibcorp.domain.model.search.SavedSearch;
import java.util.List;

public class LoadSavedSearchesUseCase {

    private final SavedSearchRepository savedSearchRepository;
    private final SavedSearchOperationExecutor operations;

    public LoadSavedSearchesUseCase(SavedSearchRepository savedSearchRepository) {
        this(savedSearchRepository, null);
    }

    public LoadSavedSearchesUseCase(SavedSearchRepository savedSearchRepository, SavedSearchOperationExecutor operations) {
        this.savedSearchRepository = savedSearchRepository;
        this.operations = operations;
    }

    public List<SavedSearch> execute() {
        if (operations == null) return savedSearchRepository.findAll();
        return operations.query(savedSearchRepository::findAll);
    }

    public List<PinnedSmartCollectionDto> executePinnedSmartCollections() {
        if (operations == null) return executePinnedSmartCollectionsLocked();
        return operations.query(this::executePinnedSmartCollectionsLocked);
    }

    private List<PinnedSmartCollectionDto> executePinnedSmartCollectionsLocked() {
        return savedSearchRepository.findAll().stream()
                .filter(SavedSearch::isSmartCollection)
                .filter(SavedSearch::isPinned)
                .sorted(java.util.Comparator.comparing(SavedSearch::getName, String.CASE_INSENSITIVE_ORDER))
                .map(saved -> new PinnedSmartCollectionDto(saved.getId(), saved.getName()))
                .toList();
    }

}