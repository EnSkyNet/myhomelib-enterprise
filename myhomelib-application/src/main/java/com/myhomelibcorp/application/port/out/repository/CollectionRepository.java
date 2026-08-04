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

    // Методи для роботи з книгами в колекції
    void addBookToCollection(String collectionId, String bookId);
    void removeBookFromCollection(String collectionId, String bookId);
    List<String> findBookIdsByCollection(String collectionId);
    boolean isBookInCollection(String collectionId, String bookId);
}