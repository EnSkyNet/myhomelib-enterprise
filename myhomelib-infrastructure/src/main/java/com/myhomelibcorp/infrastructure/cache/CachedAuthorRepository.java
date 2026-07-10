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
        authorCache.put(saved.getId(), saved);
        return saved;
    }

    @Override
    public void deleteById(AuthorId id) {
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
}