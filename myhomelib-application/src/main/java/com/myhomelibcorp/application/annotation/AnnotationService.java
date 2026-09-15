package com.myhomelibcorp.application.annotation;

import com.myhomelibcorp.application.port.out.repository.AnnotationRepository;
import com.myhomelibcorp.application.search.BookSearchIndexRefreshService;
import com.myhomelibcorp.domain.model.annotation.Annotation;
import com.myhomelibcorp.domain.model.annotation.AnnotationAnchor;
import com.myhomelibcorp.domain.model.annotation.AnnotationType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Application boundary for annotation lifecycle; UI/reader code should use this instead of SQLite. */
@Service
public class AnnotationService {
    public static final String DEFAULT_COLOR = Annotation.DEFAULT_COLOR;
    private final AnnotationRepository repository;
    private final BookSearchIndexRefreshService indexRefresh;
    private final Clock clock = Clock.systemUTC();

    public AnnotationService(AnnotationRepository repository) {
        this(repository, null);
    }

    @Autowired
    public AnnotationService(AnnotationRepository repository, BookSearchIndexRefreshService indexRefresh) {
        this.repository = repository;
        this.indexRefresh = indexRefresh;
    }

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
        return saveAndRefresh(current.withNote(note, Instant.now(clock)));
    }

    public Annotation updateColor(String annotationId, String color) {
        Annotation current = required(annotationId);
        return saveAndRefresh(current.withColor(color, Instant.now(clock)));
    }

    public Annotation updateTags(String annotationId, Set<String> tags) {
        Annotation current = required(annotationId);
        return saveAndRefresh(current.withTags(tags, Instant.now(clock)));
    }

    /** Updates all user-editable annotation fields in one repository write. */
    public Annotation update(String annotationId, String note, String color, Set<String> tags) {
        Annotation current = required(annotationId);
        Instant now = Instant.now(clock);
        return saveAndRefresh(new Annotation(
                current.id(), current.type(), current.anchor(), color, note, tags,
                current.createdAt(), now));
    }

    public Annotation reanchor(String annotationId, AnnotationAnchorData anchor) {
        if (anchor == null) throw new IllegalArgumentException("anchor is required");
        return reanchor(annotationId, anchor.toDomain());
    }

    public Annotation reanchor(String annotationId, AnnotationAnchor anchor) {
        Annotation current = required(annotationId);
        if (anchor == null) throw new IllegalArgumentException("anchor is required");
        if (!current.anchor().bookId().equals(anchor.bookId())) {
            throw new IllegalArgumentException("annotation cannot be rebound to another logical book");
        }
        return saveAndRefresh(current.withAnchor(anchor, Instant.now(clock)));
    }

    public void delete(String annotationId) {
        if (annotationId == null || annotationId.isBlank()) return;
        Annotation current = repository.findById(annotationId.trim()).orElse(null);
        repository.deleteById(annotationId.trim());
        if (current != null) refresh(current.anchor().bookId());
    }

    private Annotation create(AnnotationType type, AnnotationAnchor anchor, String color, String note, Set<String> tags) {
        Instant now = Instant.now(clock);
        Annotation annotation = new Annotation(UUID.randomUUID().toString(), type, anchor, color, note, tags, now, now);
        return saveAndRefresh(annotation);
    }

    private Annotation saveAndRefresh(Annotation annotation) {
        Annotation saved = repository.save(annotation);
        refresh(saved.anchor().bookId());
        return saved;
    }

    private void refresh(String bookId) {
        if (indexRefresh != null) indexRefresh.refreshBook(bookId);
    }

    private Annotation required(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("annotationId is required");
        return repository.findById(id.trim())
                .orElseThrow(() -> new IllegalArgumentException("Annotation not found: " + id));
    }
}
