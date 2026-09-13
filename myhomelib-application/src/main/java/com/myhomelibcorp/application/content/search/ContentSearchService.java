package com.myhomelibcorp.application.content.search;

import com.myhomelibcorp.application.content.index.ContentIndexQuery;
import com.myhomelibcorp.application.mapper.BookMapper;
import com.myhomelibcorp.application.port.out.content.ContentIndexPort;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ContentSearchService {
    private final ContentIndexPort contentIndex;
    private final BookQueryRepository books;
    private final BookMapper bookMapper;

    public ContentSearchResultPage search(String collectionId, String text, int offset, int limit) {
        String query = text == null ? "" : text.trim();
        int safeLimit = Math.max(1, Math.min(limit, 500));
        int safeOffset = Math.max(0, offset);
        if (query.isBlank()) return ContentSearchResultPage.empty(safeLimit);

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
}
