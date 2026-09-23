package com.myhomelibcorp.application.content.search;

import com.myhomelibcorp.application.content.index.*;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.content.ContentIndexPort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ContentSearchServiceTest {
    @Test
    void mapsLuceneHitToDisplayResultWithoutLeakingInfrastructure() {
        String id = "11111111-1111-1111-1111-111111111111";
        AtomicReference<ContentIndexQuery> captured = new AtomicReference<>();
        ContentIndexPort index = new ContentIndexPort() {
            @Override public void replaceArtifact(String collectionId, ContentIndexEntry entry) { }
            @Override public void deleteArtifact(String collectionId, String artifactId) { }
            @Override public void deleteBook(String collectionId, String bookId) { }
            @Override public void rebuild(String collectionId, Iterable<ContentIndexEntry> entries) { }
            @Override public ContentIndexPage search(ContentIndexQuery query) {
                captured.set(query);
                return new ContentIndexPage(List.of(new ContentIndexHit(id, "a1", "c1", "Розділ",
                        100, 260, 145, "…потрібна фраза…", 2.5f)), 1, query.offset(), query.limit());
            }
            @Override public ContentIndexHealth health(String collectionId) {
                return new ContentIndexHealth(collectionId, 1, 1, 1, true, ContentIndexHealth.State.READY, "");
            }
        };
        BookQueryRepository books = mock(BookQueryRepository.class);
        BookMapper mapper = mock(BookMapper.class);
        Book book = mock(Book.class);
        when(book.getId()).thenReturn(BookId.fromString(id));
        when(books.findListItemsByIds(List.of(BookId.fromString(id)))).thenReturn(List.of(book));
        when(mapper.toDto(book)).thenReturn(BookDto.builder().id(id).title("Книга").authorsText("Автор").build());

        ContentSearchService service = new ContentSearchService(index, books, mapper);
        ContentSearchResultPage result = service.search("collection", "фраза", 0, 25);

        assertThat(captured.get().collectionId()).isEqualTo("collection");
        assertThat(captured.get().text()).isEqualTo("фраза");
        assertThat(result.total()).isEqualTo(1);
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.bookTitle()).isEqualTo("Книга");
            assertThat(item.authors()).isEqualTo("Автор");
            assertThat(item.chapterTitle()).isEqualTo("Розділ");
            assertThat(item.snippet()).contains("фраза");
            assertThat(item.matchOffset()).isEqualTo(145);
        });
    }

    @Test
    void blankQueryDoesNotTouchIndex() {
        ContentIndexPort index = mock(ContentIndexPort.class);
        ContentSearchService service = new ContentSearchService(index, mock(BookQueryRepository.class), mock(BookMapper.class));
        assertThat(service.search("c1", "  ", 0, 20).items()).isEmpty();
        verifyNoInteractions(index);
    }
    @Test
    void rejectsContentSearchForCollectionDifferentFromActiveOneBeforeTouchingIndexOrSql() {
        ContentIndexPort index = mock(ContentIndexPort.class);
        BookQueryRepository books = mock(BookQueryRepository.class);
        CollectionLifecyclePort lifecycle = mock(CollectionLifecyclePort.class);
        when(lifecycle.getCurrentCollection()).thenReturn(new Collection(
                "active", "Active", null, null, 0, null, null, null, null));

        ContentSearchService service = new ContentSearchService(
                index, books, mock(BookMapper.class), lifecycle, new LibraryOperationCoordinator());

        assertThatThrownBy(() -> service.search("other", "needle", 0, 20))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not active");
        verifyNoInteractions(index, books);
    }

}
