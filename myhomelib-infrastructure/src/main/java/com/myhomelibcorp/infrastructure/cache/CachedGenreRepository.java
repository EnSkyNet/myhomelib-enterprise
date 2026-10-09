package com.myhomelibcorp.infrastructure.cache;

import com.myhomelibcorp.application.port.out.cache.GenreCache;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
@Primary
@RequiredArgsConstructor
@Slf4j
public class CachedGenreRepository implements GenreRepository {

    private final GenreRepository delegate;
    private final GenreCache genreCache;

    @Override
    public List<Genre> findAll() {
        return delegate.findAll();
    }

    @Override
    public Optional<Genre> findById(GenreId id) {
        return CacheAsideRepositorySupport.findById(id, genreCache, delegate::findById);
    }

    @Override
    public Genre save(Genre genre) {
        return CacheAsideRepositorySupport.saveRequired(
                genre, genreCache, delegate::save, Genre::getId);
    }

    @Override
    public void deleteById(GenreId id) {
        CacheAsideRepositorySupport.deleteById(id, genreCache, delegate::deleteById);
    }

    @Override
    public String getGenreName(String code) {
        return delegate.getGenreName(code);
    }

    @Override
    public List<String> getAllGenreNames() {
        return delegate.getAllGenreNames();
    }

    @Override
    public Map<String, String> getAllGenres() {
        return delegate.getAllGenres();
    }

    @Override
    public List<String> getAllGenreCodes() {
        return delegate.getAllGenreCodes();
    }

    @Override
    public List<Genre> getAllGenresHierarchy() {
        return delegate.getAllGenresHierarchy();
    }

    @Override
    public List<Genre> searchByName(String query, int limit) {
        return delegate.searchByName(query, limit);
    }

    @Override
    public long countOrphanedGenres() {
        return delegate.countOrphanedGenres();
    }
}