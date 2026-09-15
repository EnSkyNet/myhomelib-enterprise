package com.myhomelibcorp.application.annotation;

import com.myhomelibcorp.application.port.out.repository.AnnotationRepository;
import com.myhomelibcorp.application.search.BookSearchIndexRefreshService;
import com.myhomelibcorp.domain.model.annotation.Annotation;
import com.myhomelibcorp.domain.model.annotation.AnnotationAnchor;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

class AnnotationServiceTest {

    @Test
    void lifecycleUsesRepositoryBoundaryAndReanchorCannotCrossLogicalBook() {
        MemoryRepository repository = new MemoryRepository();
        AnnotationService service = new AnnotationService(repository);
        AnnotationAnchor anchor = new AnnotationAnchor("book-1", "artifact-1", "ch", "Chapter", "p",
                10, 15, 0.2, "hello", "before", "after");
        AnnotationAnchorData readerAnchor = new AnnotationAnchorData("book-1", "artifact-1", "ch", "Chapter", "p",
                10, 15, 0.2, "hello", "before", "after");

        Annotation created = service.createHighlight(readerAnchor, "#abcdef", Set.of("Tag"));
        assertThat(repository.findById(created.id())).contains(created);
        assertThat(created.color()).isEqualTo("#ABCDEF");
        var readerView = service.listBookAnnotationViews("book-1").getFirst();
        assertThat(readerView.id()).isEqualTo(created.id());
        assertThat(readerView.anchor().quote()).isEqualTo("hello");
        assertThat(readerView.note()).isFalse();

        AnnotationAnchor rebound = anchor.rebind("artifact-2", 20, 25, 0.4, "hello", "before", "after");
        Annotation saved = service.reanchor(created.id(), rebound);
        assertThat(saved.anchor().artifactId()).isEqualTo("artifact-2");
        assertThat(saved.anchor().startOffset()).isEqualTo(20);

        Annotation retagged = service.updateTags(created.id(),
                new java.util.LinkedHashSet<>(java.util.List.of("Review", "review", " Keep ")));
        assertThat(retagged.tags()).containsExactlyInAnyOrder("Review", "Keep");

        AnnotationAnchor anotherBook = new AnnotationAnchor("book-2", null, null, null, null,
                1, 2, 0.1, "x", "", "");
        assertThatThrownBy(() -> service.reanchor(created.id(), anotherBook))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("another logical book");
    }

    @Test
    void annotationMutationsRefreshDerivedSearchFlags() {
        MemoryRepository repository = new MemoryRepository();
        BookSearchIndexRefreshService refresh = mock(BookSearchIndexRefreshService.class);
        AnnotationService service = new AnnotationService(repository, refresh);
        String bookId = "11111111-1111-1111-1111-111111111111";
        AnnotationAnchorData anchor = new AnnotationAnchorData(bookId, null, null, "Chapter", null,
                1, 4, 0.1, "abc", "", "");

        Annotation created = service.createNote(anchor, "#FFF59D", "memo", Set.of());
        service.updateNote(created.id(), "edited");
        service.delete(created.id());

        verify(refresh, times(3)).refreshBook(bookId);
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
