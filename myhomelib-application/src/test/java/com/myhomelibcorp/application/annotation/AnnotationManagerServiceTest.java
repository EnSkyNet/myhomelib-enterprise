package com.myhomelibcorp.application.annotation;

import com.myhomelibcorp.application.port.out.annotation.AnnotationManagerQueryPort;
import com.myhomelibcorp.application.port.out.repository.AnnotationRepository;
import com.myhomelibcorp.domain.model.annotation.Annotation;
import com.myhomelibcorp.domain.model.annotation.AnnotationAnchor;
import com.myhomelibcorp.domain.model.annotation.AnnotationType;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AnnotationManagerServiceTest {

    @Test
    void queryIsBoundedAndDeleteUndoRestoresCompleteAnnotation() {
        MemoryRepository repository = new MemoryRepository();
        MemoryQueryPort queryPort = new MemoryQueryPort();
        AnnotationManagerService service = new AnnotationManagerService(queryPort, repository);

        Instant created = Instant.parse("2026-09-09T10:00:00Z");
        Annotation original = new Annotation("ann-1", AnnotationType.NOTE,
                new AnnotationAnchor("book-1", "artifact-1", "ch-1", "Chapter", "p-1",
                        10, 15, 0.25, "hello", "before", "after"),
                "#ABCDEF", "memo", Set.of("Tag", "Keep"), created, created.plusSeconds(5));
        repository.save(original);

        service.query(AnnotationManagerFilter.empty(), -50, 1000);
        assertThat(queryPort.offset).isZero();
        assertThat(queryPort.limit).isEqualTo(500);

        AnnotationUndoToken token = service.deleteForUndo("ann-1");
        assertThat(repository.findById("ann-1")).isEmpty();
        assertThat(token.anchor().artifactId()).isEqualTo("artifact-1");
        assertThat(token.tags()).containsExactlyInAnyOrder("Tag", "Keep");

        service.restoreDeleted(token);
        assertThat(repository.findById("ann-1")).contains(original);
    }

    @Test
    void editUpdatesNoteColorAndTagsAtomicallyAndPreservesAnchorAndCreatedTime() {
        MemoryRepository repository = new MemoryRepository();
        AnnotationManagerService service = new AnnotationManagerService(new MemoryQueryPort(), repository);
        Instant created = Instant.parse("2026-09-09T10:00:00Z");
        Annotation original = new Annotation("ann-2", AnnotationType.HIGHLIGHT,
                new AnnotationAnchor("book-2", null, "ch", "Chapter", null,
                        1, 4, 0.1, "abc", "", ""),
                "#FFF59D", "", Set.of("old"), created, created);
        repository.save(original);

        service.update("ann-2", "review", "#112233", Set.of("new", "work"));
        Annotation changed = repository.findById("ann-2").orElseThrow();

        assertThat(changed.anchor()).isEqualTo(original.anchor());
        assertThat(changed.createdAt()).isEqualTo(created);
        assertThat(changed.updatedAt()).isAfterOrEqualTo(created);
        assertThat(changed.note()).isEqualTo("review");
        assertThat(changed.color()).isEqualTo("#112233");
        assertThat(changed.tags()).containsExactlyInAnyOrder("new", "work");
    }

    @Test
    void noteCannotBeEditedToBlankAndMissingDeleteCannotProduceUndoToken() {
        MemoryRepository repository = new MemoryRepository();
        AnnotationManagerService service = new AnnotationManagerService(new MemoryQueryPort(), repository);
        Instant now = Instant.parse("2026-09-09T10:00:00Z");
        repository.save(new Annotation("ann-note", AnnotationType.NOTE,
                new AnnotationAnchor("book", null, null, null, null, 0, 1, 0.0, "x", "", ""),
                "#FFF59D", "note", Set.of(), now, now));

        assertThatThrownBy(() -> service.update("ann-note", "", "#FFF59D", Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("note annotation requires note text");
        assertThatThrownBy(() -> service.deleteForUndo("missing"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Annotation not found");
    }

    private static final class MemoryQueryPort implements AnnotationManagerQueryPort {
        int offset;
        int limit;
        @Override public AnnotationManagerPage query(AnnotationManagerFilter filter, int offset, int limit) {
            this.offset = offset; this.limit = limit;
            return new AnnotationManagerPage(List.of(), 0, offset, limit);
        }
        @Override public AnnotationManagerFacets facets() { return new AnnotationManagerFacets(List.of(), List.of(), List.of()); }
    }

    private static final class MemoryRepository implements AnnotationRepository {
        private final Map<String, Annotation> rows = new LinkedHashMap<>();
        @Override public Optional<Annotation> findById(String id) { return Optional.ofNullable(rows.get(id)); }
        @Override public List<Annotation> findByBookId(String bookId) {
            return rows.values().stream().filter(a -> a.anchor().bookId().equals(bookId)).toList();
        }
        @Override public Annotation save(Annotation annotation) { rows.put(annotation.id(), annotation); return annotation; }
        @Override public void deleteById(String id) { rows.remove(id); }
        @Override public long countByBookId(String bookId) { return findByBookId(bookId).size(); }
    }
}
