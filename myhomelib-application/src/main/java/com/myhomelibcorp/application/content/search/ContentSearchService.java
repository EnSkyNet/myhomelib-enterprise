package com.myhomelibcorp.application.content.search;

import com.myhomelibcorp.application.content.index.ContentIndexQuery;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.content.ContentIndexPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ContentSearchService {
    private final ContentIndexPort contentIndex;
    private final BookQueryRepository books;
    private final BookMapper bookMapper;
    private final CollectionLifecyclePort collectionLifecyclePort;
    private final LibraryOperationCoordinator operationCoordinator;

    /** Compatibility constructor for focused tests without collection lifecycle wiring. */
    public ContentSearchService(ContentIndexPort contentIndex, BookQueryRepository books, BookMapper bookMapper) {
        this(contentIndex, books, bookMapper, null, null);
    }

    @Autowired
    public ContentSearchService(ContentIndexPort contentIndex, BookQueryRepository books, BookMapper bookMapper,
                                CollectionLifecyclePort collectionLifecyclePort,
                                LibraryOperationCoordinator operationCoordinator) {
        this.contentIndex = java.util.Objects.requireNonNull(contentIndex, "contentIndex");
        this.books = java.util.Objects.requireNonNull(books, "books");
        this.bookMapper = java.util.Objects.requireNonNull(bookMapper, "bookMapper");
        this.collectionLifecyclePort = collectionLifecyclePort;
        this.operationCoordinator = operationCoordinator;
    }

    public ContentSearchResultPage search(String collectionId, String text, int offset, int limit) {
        if (operationCoordinator == null) return searchLocked(collectionId, text, offset, limit);
        var lease = operationCoordinator.acquireDetachedShared(LibraryOperationType.QUERY);
        try {
            assertExpectedCollection(collectionId);
            ContentSearchResultPage result = searchLocked(collectionId, text, offset, limit);
            lease.markCompleted("");
            return result;
        } catch (RuntimeException | Error failure) {
            lease.markFailed(failure);
            throw failure;
        } finally {
            lease.close();
        }
    }

    private ContentSearchResultPage searchLocked(String collectionId, String text, int offset, int limit) {
        String query = text == null ? "" : text.trim();
        int safeLimit = Math.max(1, Math.min(limit, 500));
        int safeOffset = Math.max(0, offset);
        if (query.isBlank()) return ContentSearchResultPage.empty(safeLimit);

        var health = contentIndex.health(collectionId);
        if (health.state() != com.myhomelibcorp.application.content.index.ContentIndexHealth.State.READY) {
            throw new ContentIndexUnavailableException(health.state());
        }

        var page = contentIndex.search(new ContentIndexQuery(collectionId, query, null, safeOffset, safeLimit));
        if (page.hits().isEmpty()) return new ContentSearchResultPage(List.of(), page.total(), safeOffset, safeLimit);

        List<BookId> orderedIds = page.hits().stream()
                .map(hit -> parseBookId(hit.bookId()))
                .filter(java.util.Objects::nonNull)
                .distinct()
                .toList();
        Map<String, com.myhomelibcorp.application.dto.BookDto> byId = new LinkedHashMap<>();
        for (var book : books.findListItemsByIds(orderedIds)) {
            if (book != null && book.getId() != null) {
                var dto = bookMapper.toDto(book);
                byId.put(book.getId().asString(), dto);
            }
        }

        var items = page.hits().stream().map(hit -> {
            var dto = byId.get(hit.bookId());
            return new ContentSearchResultItem(
                    hit.bookId(), hit.artifactId(),
                    dto == null ? hit.bookId() : dto.getTitle(),
                    dto == null ? "" : dto.getAuthorsText(),
                    hit.chapterId(), hit.chapterTitle(), hit.snippet(), hit.matchOffset(), hit.score());
        }).toList();
        return new ContentSearchResultPage(items, page.total(), page.offset(), page.limit());
    }

    private static BookId parseBookId(String value) {
        if (value == null || value.isBlank()) return null;
        try { return BookId.fromString(value); }
        catch (RuntimeException ignored) { return null; }
    }

    private void assertExpectedCollection(String expectedCollectionId) {
        if (collectionLifecyclePort == null) return;
        var active = collectionLifecyclePort.getCurrentCollection();
        if (active == null || active.getId() == null || active.getId().isBlank()) {
            throw new IllegalStateException("Колекцію не вибрано");
        }
        String expected = expectedCollectionId == null ? "" : expectedCollectionId.trim();
        if (expected.isBlank() || !expected.equals(active.getId().trim())) {
            throw new IllegalStateException("Content search requested for a collection that is not active");
        }
    }

}
