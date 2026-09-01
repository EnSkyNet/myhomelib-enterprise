package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.cache.AuthorCache;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
@Primary
@RequiredArgsConstructor
@Slf4j
public class CachedAuthorRepository implements AuthorRepository {

    private final AuthorRepository delegate;
    private final AuthorCache authorCache;

    @Override
    public List<Author> findAll() {
        return delegate.findAll();
    }

    @Override
    public Optional<Author> findById(AuthorId id) {
        if (id == null) {
            log.debug("Спроба пошуку автора з null ID");
            return Optional.empty();
        }

        Optional<Author> cached = authorCache.get(id);
        if (cached.isPresent()) {
            return cached;
        }
        Optional<Author> author = delegate.findById(id);
        author.ifPresent(a -> authorCache.put(id, a));
        return author;
    }

    @Override
    public Author save(Author author) {
        Author saved = delegate.save(author);
        if (saved != null && saved.getId() != null) {
            authorCache.put(saved.getId(), saved);
        }
        return saved;
    }

    @Override
    public void deleteById(AuthorId id) {
        if (id == null) {
            log.debug("Спроба видалення автора з null ID");
            return;
        }
        delegate.deleteById(id);
        authorCache.evict(id);
    }

    @Override
    public Optional<Author> findByFullName(String firstName, String lastName) {
        return delegate.findByFullName(firstName, lastName);
    }

    @Override
    public List<Author> findFavorites(int limit) {
        return delegate.findFavorites(limit);
    }

    @Override
    public List<Author> findByInitial(char initial) {
        return delegate.findByInitial(initial);
    }

    @Override
    public Optional<Character> findFirstInitial() {
        return delegate.findFirstInitial();
    }

    @Override
    public long countByInitial(char initial) {
        return delegate.countByInitial(initial);
    }

    @Override
    public List<Author> searchByName(String query, int limit) {
        return delegate.searchByName(query, limit);
    }

    @Override
    public List<Author> searchByName(String query, int limit, int offset) {
        return delegate.searchByName(query, limit, offset);
    }

    @Override
    public long countOrphanedAuthors() {
        return delegate.countOrphanedAuthors();
    }
}