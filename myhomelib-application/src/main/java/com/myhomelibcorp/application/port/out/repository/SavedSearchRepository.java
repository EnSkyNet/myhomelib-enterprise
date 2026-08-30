package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.domain.model.search.SavedSearch;

import java.util.List;
import java.util.Optional;

public interface SavedSearchRepository {

    List<SavedSearch> findAll();

    Optional<SavedSearch> findById(String id);

    Optional<SavedSearch> findByName(String name);

    SavedSearch save(SavedSearch search);

    void deleteById(String id);



}