package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.domain.model.publisher.Publisher;
import com.myhomelibcorp.domain.model.valueobject.PublisherId;

import java.util.List;
import java.util.Optional;

public interface PublisherRepository {
    List<Publisher> findAll();
    Optional<Publisher> findById(PublisherId id);
    Optional<Publisher> findByName(String name);
    Publisher save(Publisher publisher);
    void deleteById(PublisherId id);
    long count();
    List<Publisher> findTop(int limit);
    List<Publisher> findByNameContaining(String name);
}