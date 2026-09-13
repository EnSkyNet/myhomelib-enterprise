package com.myhomelibcorp.application.annotation;

import com.myhomelibcorp.application.port.out.annotation.AnnotationManagerQueryPort;
import com.myhomelibcorp.application.port.out.repository.AnnotationRepository;
import com.myhomelibcorp.domain.model.annotation.Annotation;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;

/** Application boundary for querying and editing the global Annotation Manager workspace. */
@Service
@RequiredArgsConstructor
public class AnnotationManagerService {
    private static final int MAX_PAGE_SIZE = 500;

    private final AnnotationManagerQueryPort queryPort;
    private final AnnotationRepository repository;
    private final Clock clock = Clock.systemUTC();

    public AnnotationManagerPage query(AnnotationManagerFilter filter, int offset, int limit) {
        AnnotationManagerFilter effective = filter == null ? AnnotationManagerFilter.empty() : filter;
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(MAX_PAGE_SIZE, limit));
        return queryPort.query(effective, safeOffset, safeLimit);
    }

    public AnnotationManagerFacets facets() {
        return queryPort.facets();
    }

    public void update(String annotationId, String note, String color, Set<String> tags) {
        Annotation current = required(annotationId);
        Instant now = Instant.now(clock);
        repository.save(new Annotation(
                current.id(), current.type(), current.anchor(), color, note, tags,
                current.createdAt(), now));
    }

    public AnnotationUndoToken deleteForUndo(String annotationId) {
        Annotation current = required(annotationId);
        AnnotationUndoToken token = snapshot(current);
        repository.deleteById(current.id());
        return token;
    }

    public void restoreDeleted(AnnotationUndoToken token) {
        if (token == null) throw new IllegalArgumentException("undo token is required");
        repository.save(new Annotation(
                token.id(), token.type().toDomain(), token.anchor().toDomain(), token.color(), token.note(), token.tags(),
                token.createdAt(), token.updatedAt()));
    }

    private Annotation required(String annotationId) {
        if (annotationId == null || annotationId.isBlank()) {
            throw new IllegalArgumentException("annotationId is required");
        }
        return repository.findById(annotationId.trim())
                .orElseThrow(() -> new IllegalArgumentException("Annotation not found: " + annotationId));
    }

    private static AnnotationUndoToken snapshot(Annotation annotation) {
        return new AnnotationUndoToken(
                annotation.id(), AnnotationManagerType.fromDomain(annotation.type()),
                AnnotationAnchorData.fromDomain(annotation.anchor()), annotation.color(), annotation.note(),
                annotation.tags(), annotation.createdAt(), annotation.updatedAt());
    }
}
