package com.myhomelibcorp.application.metadata.merge;

import com.myhomelibcorp.application.metadata.MetadataLookupService;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionLifecyclePort;
import com.myhomelibcorp.application.service.CommittedCatalogMutationService;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;

/** Bounded provider-neutral lookup and collection-bound, atomic application of reviewed fields. */
@Service
public class MetadataBatchService {
    public static final int MAX_BOOKS = 100;
    public static final int CONCURRENCY = 4;

    private final MetadataReviewService review;
    private final ApplyMetadataCandidateUseCase apply;
    private final CommittedCatalogMutationService mutations;
    private final LibraryOperationCoordinator operations;
    private final CollectionLifecyclePort collections;

    public MetadataBatchService(MetadataReviewService review, ApplyMetadataCandidateUseCase apply,
                                CommittedCatalogMutationService mutations,
                                LibraryOperationCoordinator operations, CollectionLifecyclePort collections) {
        this.review = Objects.requireNonNull(review);
        this.apply = Objects.requireNonNull(apply);
        this.mutations = Objects.requireNonNull(mutations);
        this.operations = Objects.requireNonNull(operations);
        this.collections = Objects.requireNonNull(collections);
    }

    public CompletableFuture<Result> lookup(String collectionId, List<BookId> bookIds,
                                             AtomicBoolean cancelled, Consumer<Progress> progress) {
        List<BookId> ids = normalize(bookIds);
        Objects.requireNonNull(cancelled, "cancelled");
        if (cancelled.get() || ids.isEmpty()) {
            return CompletableFuture.completedFuture(new Result(collectionId, List.of(), cancelled.get()));
        }
        LibraryOperationCoordinator.Lease lease;
        try {
            lease = operations.acquireDetached(LibraryOperationType.UPDATE);
        } catch (RuntimeException conflict) {
            return CompletableFuture.failedFuture(conflict);
        }
        try {
            requireCollection(collectionId);
            Batch batch = new Batch(ids, cancelled, progress);
            CompletableFuture<Result> result = new CompletableFuture<>();
            result.whenComplete((value, error) -> {
                if (result.isCancelled()) cancelled.set(true);
            });
            List<CompletableFuture<Void>> workers = new ArrayList<>();
            for (int i = 0; i < Math.min(CONCURRENCY, ids.size()); i++) workers.add(batch.work());
            CompletableFuture.allOf(workers.toArray(CompletableFuture[]::new)).whenComplete((ignored, error) -> {
                // Keep the collection stable until workers actually stop, even if the returned future is cancelled.
                lease.close();
                if (error != null) result.completeExceptionally(error);
                else result.complete(new Result(collectionId, batch.results(), cancelled.get()));
            });
            return result;
        } catch (RuntimeException failed) {
            lease.close();
            return CompletableFuture.failedFuture(failed);
        }
    }

    public List<Book> apply(String collectionId, List<ApplyMetadataCandidateUseCase.Request> requests,
                            AtomicBoolean cancelled) {
        Objects.requireNonNull(requests, "requests");
        Objects.requireNonNull(cancelled, "cancelled");
        if (requests.size() > MAX_BOOKS) throw new IllegalArgumentException("Metadata batch limit: " + MAX_BOOKS);
        var selected = new LinkedHashMap<BookId, ApplyMetadataCandidateUseCase.Request>();
        for (var request : requests) {
            Objects.requireNonNull(request, "request");
            if (selected.putIfAbsent(request.bookId(), request) != null) {
                throw new IllegalArgumentException("Duplicate metadata selection: " + request.bookId());
            }
        }
        selected.values().removeIf(request -> request.selectedFields().isEmpty());
        if (cancelled.get()) throw new CancellationException("Metadata update cancelled");
        if (selected.isEmpty()) return List.of();
        try (var lease = operations.acquire(LibraryOperationType.UPDATE)) {
            requireCollection(collectionId);
            return mutations.updateBatch(List.copyOf(selected.keySet()),
                    id -> apply.prepare(selected.get(id)), cancelled::get);
        }
    }

    private void requireCollection(String expected) {
        var current = collections.getCurrentCollection();
        if (expected == null || current == null || !expected.equals(current.getId())) {
            throw new IllegalStateException("Metadata operation belongs to a different collection");
        }
    }

    private static List<BookId> normalize(List<BookId> source) {
        Objects.requireNonNull(source, "bookIds");
        var unique = new LinkedHashSet<BookId>();
        for (BookId id : source) {
            unique.add(Objects.requireNonNull(id, "bookId"));
            if (unique.size() > MAX_BOOKS) throw new IllegalArgumentException("Metadata batch limit: " + MAX_BOOKS);
        }
        return List.copyOf(unique);
    }

    private final class Batch {
        private final List<BookId> ids;
        private final AtomicBoolean cancelled;
        private final Consumer<Progress> progress;
        private final Item[] items;
        private int next;
        private int completed;

        Batch(List<BookId> ids, AtomicBoolean cancelled, Consumer<Progress> progress) {
            this.ids = ids;
            this.cancelled = cancelled;
            this.progress = progress;
            this.items = new Item[ids.size()];
        }

        CompletableFuture<Void> work() {
            int index;
            synchronized (this) {
                if (cancelled.get() || next == ids.size()) return CompletableFuture.completedFuture(null);
                index = next++;
            }
            CompletableFuture<MetadataReviewLookupResult> future;
            try {
                future = Objects.requireNonNull(review.lookup(ids.get(index),
                        MetadataLookupService.DEFAULT_PROVIDER_TIMEOUT, cancelled));
            } catch (RuntimeException failure) {
                future = CompletableFuture.failedFuture(failure);
            }
            return future.handle((result, error) -> {
                record(index, new Item(ids.get(index), result, error != null || result == null));
                return null;
            }).thenCompose(ignored -> work());
        }

        private synchronized void record(int index, Item item) {
            items[index] = item;
            completed++;
            if (progress != null) {
                try { progress.accept(new Progress(completed, ids.size())); }
                catch (RuntimeException ignored) { /* Presentation failure must not interrupt other books. */ }
            }
        }

        synchronized List<Item> results() {
            List<Item> result = new ArrayList<>();
            for (Item item : items) if (item != null) result.add(item);
            return List.copyOf(result);
        }
    }

    public record Progress(int completed, int total) { }
    public record Item(BookId bookId, MetadataReviewLookupResult lookup, boolean failed) { }
    public record Result(String collectionId, List<Item> items, boolean cancelled) {
        public Result { items = List.copyOf(items); }
    }
}
