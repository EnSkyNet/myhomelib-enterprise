package com.myhomelibcorp.application.annotation;

import com.myhomelibcorp.application.port.out.repository.AnnotationRepository;
import com.myhomelibcorp.domain.model.annotation.Annotation;
import com.myhomelibcorp.domain.model.annotation.AnnotationAnchor;
import com.myhomelibcorp.domain.model.annotation.AnnotationType;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Application boundary for annotation lifecycle; UI/reader code should use this instead of SQLite. */
@Service
@RequiredArgsConstructor
public class AnnotationService {
    public static final String DEFAULT_COLOR = Annotation.DEFAULT_COLOR;
    private final AnnotationRepository repository;
    private final Clock clock = Clock.systemUTC();

    public Annotation createHighlight(AnnotationAnchor anchor, String color, Set<String> tags) {
        return create(AnnotationType.HIGHLIGHT, anchor, color, "", tags);
    }

    public Annotation createHighlight(AnnotationAnchorData anchor, String color, Set<String> tags) {
        if (anchor == null) throw new IllegalArgumentException("anchor is required");
        return createHighlight(anchor.toDomain(), color, tags);
    }

    public Annotation createNote(AnnotationAnchor anchor, String color, String note, Set<String> tags) {
        return create(AnnotationType.NOTE, anchor, color, note, tags);
    }

    public Annotation createNote(AnnotationAnchorData anchor, String color, String note, Set<String> tags) {
        if (anchor == null) throw new IllegalArgumentException("anchor is required");
        return createNote(anchor.toDomain(), color, note, tags);
    }

    public List<Annotation> listBookAnnotations(String bookId) {
        if (bookId == null || bookId.isBlank()) return List.of();
        return repository.findByBookId(bookId.trim());
    }

    public List<AnnotationReaderItem> listBookAnnotationViews(String bookId) {
        return listBookAnnotations(bookId).stream().map(AnnotationReaderItem::fromDomain).toList();
    }

    public Annotation updateNote(String annotationId, String note) {
        Annotation current = required(annotationId);
        return repository.save(current.withNote(note, Instant.now(clock)));
    }

    public Annotation updateColor(String annotationId, String color) {
        Annotation current = required(annotationId);
        return repository.save(current.withColor(color, Instant.now(clock)));
    }

    public Annotation updateTags(String annotationId, Set<String> tags) {
        Annotation current = required(annotationId);
        return repository.save(current.withTags(tags, Instant.now(clock)));
    }

    public Annotation reanchor(String annotationId, AnnotationAnchor anchor) {
        Annotation current = required(annotationId);
        if (anchor == null) throw new IllegalArgumentException("anchor is required");
        if (!current.anchor().bookId().equals(anchor.bookId())) {
            throw new IllegalArgumentException("annotation cannot be rebound to another logical book");
        }
        return repository.save(current.withAnchor(anchor, Instant.now(clock)));
    }

    public void delete(String annotationId) {
        if (annotationId == null || annotationId.isBlank()) return;
        repository.deleteById(annotationId.trim());
    }

    private Annotation create(AnnotationType type, AnnotationAnchor anchor, String color, String note, Set<String> tags) {
        Instant now = Instant.now(clock);
        Annotation annotation = new Annotation(UUID.randomUUID().toString(), type, anchor, color, note, tags, now, now);
        return repository.save(annotation);
    }

    private Annotation required(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("annotationId is required");
        return repository.findById(id.trim())
                .orElseThrow(() -> new IllegalArgumentException("Annotation not found: " + id));
    }
}
