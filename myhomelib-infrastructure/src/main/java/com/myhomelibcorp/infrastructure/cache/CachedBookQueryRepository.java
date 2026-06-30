package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.BookQueryRepository;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Repository
@Primary
@RequiredArgsConstructor
@Slf4j
public class CachedBookQueryRepository implements BookQueryRepository {

    private final BookQueryRepository delegate; // SqliteBookQueryRepository
    private final BookCache bookCache;

    @Override
    public Optional<Book> findById(BookId id) {
        Optional<Book> cached = bookCache.get(id);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<Book> book = delegate.findById(id);
        book.ifPresent(b -> bookCache.put(id, b));
        return book;
    }

    @Override
    public List<Book> findByIds(List<BookId> ids) {
        List<Book> result = new ArrayList<>();
        List<BookId> missingIds = new ArrayList<>();

        for (BookId id : ids) {
            Optional<Book> cached = bookCache.get(id);
            if (cached.isPresent()) {
                result.add(cached.get());
            } else {
                missingIds.add(id);
            }
        }

        if (missingIds.isEmpty()) {
            return result;
        }

        List<Book> loaded = delegate.findByIds(missingIds);
        for (Book book : loaded) {
            bookCache.put(book.getId(), book);
        }
        result.addAll(loaded);
        return result;
    }

    @Override
    public List<Book> findAll(int limit, int offset) {
        // Не кешуємо findAll через потенційно великий обсяг
        return delegate.findAll(limit, offset);
    }

    @Override
    public List<Book> findByAuthorId(AuthorId authorId, int limit, int offset) {
        // Можна кешувати, але поки пропускаємо
        return delegate.findByAuthorId(authorId, limit, offset);
    }

    @Override
    public List<Book> search(String query, int limit) {
        return delegate.search(query, limit);
    }

    @Override
    public List<Book> searchByAuthor(String authorName, int limit) {
        return delegate.searchByAuthor(authorName, limit);
    }

    @Override
    public Optional<Book> findByTitleAndAuthor(String title, String authorLastName) {
        return delegate.findByTitleAndAuthor(title, authorLastName);
    }

    @Override
    public int getTotalCount() {
        return delegate.getTotalCount();
    }

    @Override
    public List<Book> findBySeries(String seriesName, int limit, int offset) {
        return delegate.findBySeries(seriesName, limit, offset);
    }

    @Override
    public List<Book> findByGenre(String genreCode, int limit, int offset) {
        return delegate.findByGenre(genreCode, limit, offset);
    }
}