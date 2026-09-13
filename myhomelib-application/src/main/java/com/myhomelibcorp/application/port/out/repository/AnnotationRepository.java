package com.myhomelibcorp.application.port.out.repository;

import com.myhomelibcorp.domain.model.annotation.Annotation;

import java.util.List;
import java.util.Optional;

public interface AnnotationRepository {
    Optional<Annotation> findById(String id);
    List<Annotation> findByBookId(String bookId);
    Annotation save(Annotation annotation);
    void deleteById(String id);
    long countByBookId(String bookId);
}
