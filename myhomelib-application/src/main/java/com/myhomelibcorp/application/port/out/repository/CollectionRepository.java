package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.domain.model.collection.Collection;

import java.util.List;
import java.util.Optional;

public interface CollectionRepository {
    List<Collection> findAll();
    Optional<Collection> findById(String id);
    Optional<Collection> findByName(String name);
    Collection save(Collection collection);
    void deleteById(String id);
}