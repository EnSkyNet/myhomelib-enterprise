package com.myhomelibcorp.application.annotation;

import com.myhomelibcorp.application.port.out.annotation.AnnotationManagerQueryPort;
import com.myhomelibcorp.application.port.out.repository.AnnotationRepository;
import com.myhomelibcorp.application.search.BookSearchIndexRefreshService;
import com.myhomelibcorp.domain.model.annotation.Annotation;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Function;

/** Application boundary for querying and editing the global Annotation Manager workspace. */
@Service
public class AnnotationManagerService {
    private static final int MAX_PAGE_SIZE = 500;

    private final AnnotationManagerQueryPort queryPort;
    private final AnnotationRepository repository;
    private final BookSearchIndexRefreshService indexRefresh;
    private final Clock clock = Clock.systemUTC();

    public AnnotationManagerService(AnnotationManagerQueryPort queryPort, AnnotationRepository repository) {
        this(queryPort, repository, null);
    }

    @Autowired
    public AnnotationManagerService(AnnotationManagerQueryPort queryPort, AnnotationRepository repository,
                                    BookSearchIndexRefreshService indexRefresh) {
        this.queryPort = queryPort;
        this.repository = repository;
        this.indexRefresh = indexRefresh;
    }

    public AnnotationManagerPage query(AnnotationManagerFilter filter, int offset, int limit) {
        AnnotationManagerFilter effective = filter == null ? AnnotationManagerFilter.empty() : filter;
        int safeOffset = Math.max(0, offset);
        int safeLimit = Math.max(1, Math.min(MAX_PAGE_SIZE, limit));
        return queryPort.query(effective, safeOffset, safeLimit);
    }

    public AnnotationManagerFacets facets() {
        return queryPort.facets();
    }

    public void update(String annotationId, String note, String color, Set<String> tags) {
        Annotation current = required(annotationId);
        Instant now = Instant.now(clock);
        repository.save(new Annotation(
                current.id(), current.type(), current.anchor(), color, note, tags,
                current.createdAt(), now));
        refreshBooks(List.of(current.anchor().bookId()));
    }

    public AnnotationUndoToken deleteForUndo(String annotationId) {
        Annotation current = required(annotationId);
        AnnotationUndoToken token = snapshot(current);
        repository.deleteById(current.id());
        refreshBooks(List.of(current.anchor().bookId()));
        return token;
    }

    /** Deletes the requested annotations as one logical operation and returns one undo token. */
    public AnnotationBatchUndoToken deleteForUndo(Collection<String> annotationIds) {
        List<String> ids = normalizedIds(annotationIds);
        if (ids.isEmpty()) throw new IllegalArgumentException("annotationIds are required");

        List<AnnotationUndoToken> snapshots = ids.stream()
                .map(this::required)
                .map(AnnotationManagerService::snapshot)
                .toList();
        List<AnnotationUndoToken> deleted = new ArrayList<>();
        try {
            for (AnnotationUndoToken token : snapshots) {
                repository.deleteById(token.id());
                deleted.add(token);
            }
            refreshBooks(snapshots.stream().map(token -> token.anchor().bookId()).toList());
            return new AnnotationBatchUndoToken(snapshots);
        } catch (RuntimeException failure) {
            // Keep the operation all-or-nothing even when the repository has no surrounding transaction.
            for (int i = deleted.size() - 1; i >= 0; i--) {
                try {
                    restoreDeleted(deleted.get(i));
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    public void restoreDeleted(AnnotationUndoToken token) {
        if (token == null) throw new IllegalArgumentException("undo token is required");
        repository.save(fromSnapshot(token));
        refreshBooks(List.of(token.anchor().bookId()));
    }

    /** Restores every annotation deleted by one batch operation as one logical operation. */
    public void restoreDeleted(AnnotationBatchUndoToken token) {
        if (token == null) throw new IllegalArgumentException("undo token is required");
        List<AnnotationUndoToken> restored = new ArrayList<>();
        try {
            for (AnnotationUndoToken item : token.annotations()) {
                repository.save(fromSnapshot(item));
                restored.add(item);
            }
            refreshBooks(token.annotations().stream().map(item -> item.anchor().bookId()).toList());
        } catch (RuntimeException failure) {
            // If an undo fails half way, put the repository back into the pre-undo (deleted) state.
            for (int i = restored.size() - 1; i >= 0; i--) {
                try {
                    repository.deleteById(restored.get(i).id());
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }

    /** Applies one color to every selected annotation while preserving note text and tags. */
    public int updateColor(Collection<String> annotationIds, String color) {
        Instant now = Instant.now(clock);
        return updateBatch(annotationIds, current -> current.withColor(color, now));
    }

    /** Replaces tags on every selected annotation while preserving all other fields. */
    public int updateTags(Collection<String> annotationIds, Set<String> tags) {
        Set<String> normalized = tags == null ? Set.of() : Set.copyOf(tags);
        Instant now = Instant.now(clock);
        return updateBatch(annotationIds, current -> current.withTags(normalized, now));
    }

    /** Adds tags to every selected annotation without removing existing tags. */
    public int addTags(Collection<String> annotationIds, Set<String> tags) {
        Set<String> additions = tags == null ? Set.of() : Set.copyOf(tags);
        Instant now = Instant.now(clock);
        return updateBatch(annotationIds, current -> {
            LinkedHashSet<String> merged = new LinkedHashSet<>(current.tags());
            merged.addAll(additions);
            return current.withTags(merged, now);
        });
    }

    /** Removes the requested tags from every selected annotation without touching other tags. */
    public int removeTags(Collection<String> annotationIds, Set<String> tags) {
        Set<String> removals = tags == null ? Set.of() : Set.copyOf(tags);
        Instant now = Instant.now(clock);
        return updateBatch(annotationIds, current -> {
            LinkedHashSet<String> remaining = new LinkedHashSet<>(current.tags());
            remaining.removeAll(removals);
            return current.withTags(remaining, now);
        });
    }

    /**
     * Applies one mutation to a validated batch. If a repository write fails, already written rows
     * are restored from their original snapshots so the UI never reports a half-applied batch.
     */
    private int updateBatch(Collection<String> annotationIds, Function<Annotation, Annotation> mutation) {
        List<String> ids = normalizedIds(annotationIds);
        if (ids.isEmpty()) return 0;
        List<Annotation> originals = ids.stream().map(this::required).toList();
        List<Annotation> written = new ArrayList<>();
        try {
            for (Annotation current : originals) {
                repository.save(mutation.apply(current));
                written.add(current);
            }
            refreshBooks(originals.stream().map(item -> item.anchor().bookId()).toList());
            return originals.size();
        } catch (RuntimeException failure) {
            for (int i = written.size() - 1; i >= 0; i--) {
                try {
                    repository.save(written.get(i));
                } catch (RuntimeException rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }


    private void refreshBooks(Collection<String> bookIds) {
        if (indexRefresh != null) indexRefresh.refreshBooks(bookIds);
    }

    private static List<String> normalizedIds(Collection<String> annotationIds) {
        if (annotationIds == null || annotationIds.isEmpty()) return List.of();
        LinkedHashSet<String> result = new LinkedHashSet<>();
        for (String value : annotationIds) {
            if (value != null && !value.isBlank()) result.add(value.trim());
        }
        return List.copyOf(result);
    }

    private static Annotation fromSnapshot(AnnotationUndoToken token) {
        return new Annotation(
                token.id(), token.type().toDomain(), token.anchor().toDomain(), token.color(), token.note(), token.tags(),
                token.createdAt(), token.updatedAt());
    }

    private Annotation required(String annotationId) {
        if (annotationId == null || annotationId.isBlank()) {
            throw new IllegalArgumentException("annotationId is required");
        }
        return repository.findById(annotationId.trim())
                .orElseThrow(() -> new IllegalArgumentException("Annotation not found: " + annotationId));
    }

    private static AnnotationUndoToken snapshot(Annotation annotation) {
        return new AnnotationUndoToken(
                annotation.id(), AnnotationManagerType.fromDomain(annotation.type()),
                AnnotationAnchorData.fromDomain(annotation.anchor()), annotation.color(), annotation.note(),
                annotation.tags(), annotation.createdAt(), annotation.updatedAt());
    }
}
