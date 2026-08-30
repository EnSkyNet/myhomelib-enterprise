package com.myhomelibcorp.application.imports.duplicate;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.DuplicateBookCandidate;
import com.myhomelibcorp.application.port.out.repository.DuplicateBookLookup;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Component
@RequiredArgsConstructor
@Slf4j
public class DuplicateDetector {

    private final DuplicateBookLookup duplicateBookLookup;
    private final BookQueryRepository bookQueryRepository;

    private final Set<DuplicateKey> batchKeyCache = new LinkedHashSet<>();
    private static final int MAX_CACHE_SIZE = 50_000;

    public boolean isDuplicate(Book book) {
        if (!canMatch(book)) return false;
        DuplicateKey key = buildNaturalKey(book);
        if (batchKeyCache.contains(key)) return true;
        return findDuplicate(book).isPresent();
    }

    public Optional<Book> findDuplicate(Book book) {
        if (!canMatch(book)) return Optional.empty();
        BatchResolution resolution = resolveBatch(List.of(book));
        return Optional.ofNullable(resolution.existingByIncomingId().get(book.getId()));
    }

    /**
     * Resolves DB duplicates for one bounded import batch and separately marks duplicate
     * natural keys that occur more than once inside the incoming batch. This avoids both
     * one-query-per-book N+1 behaviour and duplicate rows within a single batch.
     */
    public BatchResolution resolveBatch(List<Book> books) {
        if (books == null || books.isEmpty()) return BatchResolution.empty();

        LinkedHashMap<DuplicateBookCandidate, DuplicateKey> candidateKeys = new LinkedHashMap<>();
        Map<BookId, DuplicateBookCandidate> candidateByIncomingId = new HashMap<>();
        Set<BookId> repeatedIncomingIds = new HashSet<>();
        Set<DuplicateKey> seenInBatch = new HashSet<>();

        for (Book book : books) {
            if (!canMatch(book)) continue;
            DuplicateKey naturalKey = buildNaturalKey(book);
            if (!seenInBatch.add(naturalKey)) {
                repeatedIncomingIds.add(book.getId());
                continue;
            }
            DuplicateBookCandidate candidate = candidate(book);
            candidateKeys.putIfAbsent(candidate, naturalKey);
            candidateByIncomingId.put(book.getId(), candidate);
        }

        if (candidateKeys.isEmpty()) {
            return new BatchResolution(Map.of(), Set.copyOf(repeatedIncomingIds));
        }

        Map<DuplicateBookCandidate, BookId> duplicateIds =
                duplicateBookLookup.findDuplicateIds(new ArrayList<>(candidateKeys.keySet()));
        if (duplicateIds.isEmpty()) {
            return new BatchResolution(Map.of(), Set.copyOf(repeatedIncomingIds));
        }

        List<BookId> existingIds = duplicateIds.values().stream().distinct().toList();
        Map<BookId, Book> existingById = new HashMap<>();
        for (Book existing : bookQueryRepository.findByIds(existingIds)) {
            existingById.put(existing.getId(), existing);
        }

        Map<BookId, Book> existingByIncoming = new HashMap<>();
        for (Map.Entry<BookId, DuplicateBookCandidate> entry : candidateByIncomingId.entrySet()) {
            BookId existingId = duplicateIds.get(entry.getValue());
            if (existingId == null) continue;
            Book existing = existingById.get(existingId);
            if (existing != null) existingByIncoming.put(entry.getKey(), existing);
        }
        return new BatchResolution(Map.copyOf(existingByIncoming), Set.copyOf(repeatedIncomingIds));
    }

    public void addKey(Book book) {
        if (!canMatch(book)) return;
        batchKeyCache.add(buildNaturalKey(book));
        evictOldestIfNeeded();
    }

    public void addAllKeys(List<Book> books) {
        if (books == null || books.isEmpty()) return;
        for (Book book : books) {
            if (canMatch(book)) batchKeyCache.add(buildNaturalKey(book));
        }
        evictOldestIfNeeded();
    }

    public void clearCache() {
        batchKeyCache.clear();
        log.debug("Кеш дублікатів очищено");
    }

    private void evictOldestIfNeeded() {
        while (batchKeyCache.size() > MAX_CACHE_SIZE) {
            var iterator = batchKeyCache.iterator();
            if (!iterator.hasNext()) return;
            iterator.next();
            iterator.remove();
        }
    }

    private static boolean canMatch(Book book) {
        return book != null && book.getAuthors() != null && !book.getAuthors().isEmpty();
    }

    private static DuplicateBookCandidate candidate(Book book) {
        return new DuplicateBookCandidate(
                book.getTitle(),
                book.getAuthors().get(0).getLastName());
    }

    private DuplicateKey buildNaturalKey(Book book) {
        String firstAuthor = book.getAuthors().stream()
                .findFirst()
                .map(Author::getLastName)
                .orElse("");
        return new DuplicateKey(normalize(book.getTitle()), normalize(firstAuthor));
    }

    private static String normalize(String value) {
        if (value == null || value.isBlank()) return "";
        return Normalizer.normalize(value, Normalizer.Form.NFKC)
                .strip()
                .replaceAll("\\s+", " ")
                .toLowerCase(Locale.ROOT);
    }

    public record BatchResolution(Map<BookId, Book> existingByIncomingId,
                                  Set<BookId> repeatedIncomingIds) {
        public static BatchResolution empty() {
            return new BatchResolution(Map.of(), Set.of());
        }

        public boolean isRepeated(Book book) {
            return book != null && repeatedIncomingIds.contains(book.getId());
        }

        public Optional<Book> existingFor(Book book) {
            if (book == null) return Optional.empty();
            return Optional.ofNullable(existingByIncomingId.get(book.getId()));
        }
    }

    private record DuplicateKey(String title, String firstAuthorLastName) { }
}
