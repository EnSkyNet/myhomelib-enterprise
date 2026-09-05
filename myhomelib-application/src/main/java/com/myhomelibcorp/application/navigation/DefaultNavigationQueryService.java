package com.myhomelibcorp.application.navigation;

import com.myhomelibcorp.application.catalog.CatalogUpdateService;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.NavigationFacetRepository;
import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.filter.BookFilterStateService;
import com.myhomelibcorp.application.query.book.BookQuery;
import com.myhomelibcorp.application.query.common.PageResult;
import com.myhomelibcorp.domain.model.valueobject.LanguageCode;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@Service
public class DefaultNavigationQueryService implements NavigationQueryService {

    private static final Comparator<NavigationNodeDto> LABEL_ORDER = Comparator
            .comparing((NavigationNodeDto node) -> normalize(node.label()))
            .thenComparing(NavigationNodeDto::id);

    private final AuthorRepository authorRepository;
    private final BookQueryRepository bookQueryRepository;
    private final NavigationFacetRepository navigationFacetRepository;
    private final BookFilterStateService filterStateService;
    private final CatalogUpdateService catalogUpdateService;
    private final ExecutorPort executorPort;

    public DefaultNavigationQueryService(
            AuthorRepository authorRepository,
            BookQueryRepository bookQueryRepository,
            NavigationFacetRepository navigationFacetRepository,
            BookFilterStateService filterStateService,
            CatalogUpdateService catalogUpdateService,
            ExecutorPort executorPort) {
        this.authorRepository = authorRepository;
        this.bookQueryRepository = bookQueryRepository;
        this.navigationFacetRepository = navigationFacetRepository;
        this.filterStateService = filterStateService;
        this.catalogUpdateService = catalogUpdateService;
        this.executorPort = executorPort;
    }

    @Override
    public java.util.concurrent.CompletableFuture<List<NavigationNodeDto>> load(NavigationMode mode, Character initial) {
        if (mode == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalArgumentException("Navigation mode cannot be null"));
        }
        return executorPort.submit(() -> {
            BookFilterSpec filter = filterStateService.current();
            return switch (mode) {
                case AUTHORS -> loadAuthors(initial, filter);
                case SERIES -> loadSeries(filter);
                case GENRES -> loadGenres(filter);
                case YEARS -> loadYears(filter);
                case LANGUAGES -> loadLanguages(filter);
                case ARCHIVES -> loadArchives(filter);
                case KEYWORDS -> loadKeywords(filter);
                case GROUPS -> loadGroups(filter);
                case REVIEWS -> loadReviews(filter);
                case UPDATES -> loadUpdates();
                case DOWNLOADED -> loadDownloadedAuthors(filter);
                case ALREADY_READ -> loadAlreadyRead(filter);
                case HISTORY -> loadHistory(filter);
                case ALL_BOOKS -> loadAllBooks(filter);
            };
        });
    }

    private List<NavigationNodeDto> loadAuthors(Character initial, BookFilterSpec filter) {
        Character selected = initial;
        if (selected == null || selected == '*') {
            selected = navigationFacetRepository.findFirstAuthorInitial(filter).orElse(null);
        }
        if (selected == null) return List.of();

        return navigationFacetRepository.findAuthors(selected, filter).stream()
                .map(facet -> new NavigationNodeDto(
                        NavigationMode.AUTHORS, facet.id(), facet.label(), facet.bookCount()))
                .filter(DefaultNavigationQueryService::hasLabel)
                .toList();
    }

    @Override
    public java.util.concurrent.CompletableFuture<PageResult<NavigationNodeDto>> loadAuthorsPage(
            Character initial, int limit, int offset) {
        return executorPort.submit(() -> {
            BookFilterSpec filter = filterStateService.current();
            Character selected = initial;
            if (selected == null || selected == '*') {
                selected = navigationFacetRepository.findFirstAuthorInitial(filter).orElse(null);
            }
            if (selected == null) return PageResult.empty();

            int safeLimit = Math.max(1, Math.min(limit, 1000));
            int safeOffset = Math.max(0, offset);
            var page = navigationFacetRepository.findAuthorsPage(selected, filter, safeLimit, safeOffset);
            List<NavigationNodeDto> nodes = page.content().stream()
                    .map(facet -> new NavigationNodeDto(
                            NavigationMode.AUTHORS, facet.id(), facet.label(), facet.bookCount()))
                    .filter(DefaultNavigationQueryService::hasLabel)
                    .toList();
            int pageNumber = safeOffset / safeLimit;
            return PageResult.of(nodes, page.totalElements(), pageNumber, safeLimit);
        });
    }

    @Override
    public java.util.concurrent.CompletableFuture<NavigationQueryService.AuthorPage> loadAuthorsAfter(
            Character initial, int limit, NavigationQueryService.AuthorCursor after) {
        return executorPort.submit(() -> {
            BookFilterSpec filter = filterStateService.current();
            Character selected = initial;
            if (selected == null || selected == '*') {
                selected = navigationFacetRepository.findFirstAuthorInitial(filter).orElse(null);
            }
            if (selected == null) return NavigationQueryService.AuthorPage.empty();

            int safeLimit = Math.max(1, Math.min(limit, 1000));
            NavigationFacetRepository.AuthorCursor repositoryCursor = after == null ? null
                    : new NavigationFacetRepository.AuthorCursor(
                            after.lastName(), after.firstName(), after.middleName(), after.id());
            var slice = navigationFacetRepository.findAuthorsAfter(selected, filter, safeLimit, repositoryCursor);
            List<NavigationNodeDto> nodes = slice.content().stream()
                    .map(facet -> new NavigationNodeDto(
                            NavigationMode.AUTHORS, facet.id(), facet.label(), facet.bookCount()))
                    .filter(DefaultNavigationQueryService::hasLabel)
                    .toList();
            NavigationQueryService.AuthorCursor next = slice.nextCursor() == null ? null
                    : new NavigationQueryService.AuthorCursor(
                            slice.nextCursor().lastName(), slice.nextCursor().firstName(),
                            slice.nextCursor().middleName(), slice.nextCursor().id());
            return new NavigationQueryService.AuthorPage(nodes, slice.totalElements(), next);
        });
    }

    @Override
    public java.util.concurrent.CompletableFuture<List<NavigationNodeDto>> searchAuthors(String query, int limit) {
        String normalized = query == null ? "" : query.trim();
        if (normalized.isBlank()) return java.util.concurrent.CompletableFuture.completedFuture(List.of());
        int safeLimit = Math.max(1, Math.min(limit, 500));
        var filter = filterStateService.current();

        // The common sidebar path has no active book filter. Searching the normalized
        // authors table directly avoids joining/aggregating hundreds of thousands of
        // books for every keystroke. This is the same fast author lookup used by the
        // main Search workspace.
        if (filter == null || !filter.isActive()) {
            return executorPort.submit(() -> authorRepository
                    .searchByName(normalized, safeLimit)
                    .stream()
                    .map(author -> NavigationNodeDto.of(
                            NavigationMode.AUTHORS,
                            author.getId().asString(),
                            author.getFullName()))
                    .filter(DefaultNavigationQueryService::hasLabel)
                    .toList());
        }

        // With a global book filter active we must preserve navigation semantics. The
        // repository uses EXISTS (not COUNT/GROUP BY) and therefore still avoids the
        // previous full aggregation hot path.
        return executorPort.submit(() -> navigationFacetRepository
                .searchAuthors(normalized, filter, safeLimit)
                .stream()
                .map(facet -> new NavigationNodeDto(
                        NavigationMode.AUTHORS, facet.id(), facet.label(), facet.bookCount()))
                .filter(DefaultNavigationQueryService::hasLabel)
                .toList());
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.Optional<Character>> findFirstAuthorInitial() {
        return executorPort.submit(() -> navigationFacetRepository.findFirstAuthorInitial(filterStateService.current()));
    }

    @Override
    public java.util.concurrent.CompletableFuture<java.util.Optional<Character>> findAuthorInitial(String authorId) {
        if (authorId == null || authorId.isBlank()) {
            return java.util.concurrent.CompletableFuture.completedFuture(java.util.Optional.empty());
        }
        return executorPort.submit(() -> authorRepository
                .findById(com.myhomelibcorp.domain.model.valueobject.AuthorId.fromString(authorId))
                .map(author -> {
                    String label = author.getFullName();
                    if (label == null || label.isBlank()) return '#';
                    char first = label.trim().charAt(0);
                    return Character.isLetter(first) ? Character.toUpperCase(first) : '#';
                }));
    }

    private List<NavigationNodeDto> loadDownloadedAuthors(BookFilterSpec filter) {
        return navigationFacetRepository.findDownloadedAuthors(filter).stream()
                .map(facet -> new NavigationNodeDto(
                        NavigationMode.DOWNLOADED, facet.id(), facet.label(), facet.bookCount()))
                .filter(DefaultNavigationQueryService::hasLabel)
                .toList();
    }

    private List<NavigationNodeDto> loadSeries(BookFilterSpec filter) {
        return navigationFacetRepository.findSeries(filter).stream()
                .map(facet -> new NavigationNodeDto(
                        NavigationMode.SERIES, facet.id(), facet.label(), facet.bookCount()))
                .filter(DefaultNavigationQueryService::hasLabel)
                .sorted(LABEL_ORDER)
                .toList();
    }

    private List<NavigationNodeDto> loadGenres(BookFilterSpec filter) {
        return navigationFacetRepository.findGenres(filter).stream()
                .map(facet -> new NavigationNodeDto(
                        NavigationMode.GENRES, facet.id(), facet.label(), facet.bookCount()))
                .filter(DefaultNavigationQueryService::hasLabel)
                .sorted(LABEL_ORDER)
                .toList();
    }

    private List<NavigationNodeDto> loadYears(BookFilterSpec filter) {
        return navigationFacetRepository.findYears(filter).stream()
                .map(facet -> new NavigationNodeDto(
                        NavigationMode.YEARS,
                        facet.id(),
                        facet.label(),
                        facet.bookCount()))
                .filter(DefaultNavigationQueryService::hasLabel)
                .sorted(Comparator.comparingInt(DefaultNavigationQueryService::yearValue).reversed()
                        .thenComparing(NavigationNodeDto::id))
                .toList();
    }

    private List<NavigationNodeDto> loadLanguages(BookFilterSpec filter) {
        return navigationFacetRepository.findLanguages(filter).stream()
                .map(DefaultNavigationQueryService::normalizeLanguageFacet)
                .filter(java.util.Objects::nonNull)
                .sorted(LABEL_ORDER)
                .toList();
    }

    private List<NavigationNodeDto> loadArchives(BookFilterSpec filter) {
        List<NavigationFacetRepository.ArchiveFacet> facets = navigationFacetRepository.findArchives(filter);
        Map<String, Integer> baseNameCounts = new HashMap<>();
        for (var facet : facets) {
            baseNameCounts.merge(archiveBaseName(facet.archivePath()).toLowerCase(Locale.ROOT), 1, Integer::sum);
        }

        return facets.stream()
                .map(facet -> {
                    ArchiveNavigationKey key = new ArchiveNavigationKey(facet.collectionRoot(), facet.archivePath());
                    String base = archiveBaseName(facet.archivePath());
                    String label = baseNameCounts.getOrDefault(base.toLowerCase(Locale.ROOT), 0) > 1
                            ? archiveDisambiguatedLabel(facet.archivePath())
                            : base;
                    return new NavigationNodeDto(
                            NavigationMode.ARCHIVES,
                            key.encode(),
                            label,
                            facet.bookCount());
                })
                .filter(DefaultNavigationQueryService::hasLabel)
                .sorted(LABEL_ORDER)
                .toList();
    }

    private List<NavigationNodeDto> loadKeywords(BookFilterSpec filter) {
        return navigationFacetRepository.findKeywords(filter).stream()
                .map(facet -> new NavigationNodeDto(
                        NavigationMode.KEYWORDS, facet.id(), facet.label(), facet.bookCount()))
                .filter(DefaultNavigationQueryService::hasLabel)
                .sorted(LABEL_ORDER)
                .toList();
    }

    private List<NavigationNodeDto> loadGroups(BookFilterSpec filter) {
        return navigationFacetRepository.findGroups(filter).stream()
                .map(facet -> new NavigationNodeDto(
                        NavigationMode.GROUPS, facet.id(), facet.label(), facet.bookCount()))
                .filter(DefaultNavigationQueryService::hasLabel)
                .sorted(LABEL_ORDER)
                .toList();
    }

    private List<NavigationNodeDto> loadReviews(BookFilterSpec filter) {
        Map<String, Integer> order = Map.of(
                ReviewNavigationFilter.RATED.id(), 0,
                ReviewNavigationFilter.REVIEWED.id(), 1,
                ReviewNavigationFilter.RATED_AND_REVIEWED.id(), 2);
        return navigationFacetRepository.findReviewSubsets(filter).stream()
                .map(facet -> new NavigationNodeDto(
                        NavigationMode.REVIEWS, facet.id(), facet.label(), facet.bookCount()))
                .filter(node -> {
                    try {
                        ReviewNavigationFilter.fromId(node.id());
                        return true;
                    } catch (IllegalArgumentException ignored) {
                        return false;
                    }
                })
                .sorted(Comparator.comparingInt(node -> order.getOrDefault(node.id(), Integer.MAX_VALUE)))
                .toList();
    }


    private List<NavigationNodeDto> loadUpdates() {
        long count = catalogUpdateService.pendingUpdateCount();
        return List.of(new NavigationNodeDto(
                NavigationMode.UPDATES,
                "updates",
                "Оновлення",
                count));
    }

    private List<NavigationNodeDto> loadAlreadyRead(BookFilterSpec filter) {
        long count = bookQueryRepository.count(BookQuery.builder().onlyRead(true).filterSpec(filter).build());
        return List.of(new NavigationNodeDto(
                NavigationMode.ALREADY_READ,
                "already-read",
                "already-read",
                count));
    }

    private List<NavigationNodeDto> loadHistory(BookFilterSpec filter) {
        long count = bookQueryRepository.count(BookQuery.builder().onlyInHistory(true).filterSpec(filter).build());
        return List.of(new NavigationNodeDto(
                NavigationMode.HISTORY,
                "history",
                "history",
                count));
    }

    private List<NavigationNodeDto> loadAllBooks(BookFilterSpec filter) {
        long count = bookQueryRepository.count(BookQuery.builder().filterSpec(filter).build());
        return List.of(new NavigationNodeDto(
                NavigationMode.ALL_BOOKS,
                "all",
                "all-books",
                count));
    }

    private static NavigationNodeDto normalizeLanguageFacet(NavigationFacetRepository.Facet facet) {
        try {
            String code = LanguageCode.of(facet.id()).toString();
            return new NavigationNodeDto(NavigationMode.LANGUAGES, code, code, facet.bookCount());
        } catch (IllegalArgumentException ignored) {
            return null;
        }
    }

    private static boolean hasLabel(NavigationNodeDto node) {
        return node.label() != null && !node.label().isBlank();
    }

    private static int yearValue(NavigationNodeDto node) {
        try {
            return Integer.parseInt(node.id());
        } catch (NumberFormatException e) {
            return Integer.MIN_VALUE;
        }
    }

    private static String archiveBaseName(String archivePath) {
        String normalized = archivePath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        return slash >= 0 ? normalized.substring(slash + 1) : normalized;
    }

    private static String archiveDisambiguatedLabel(String archivePath) {
        String normalized = archivePath.replace('\\', '/');
        int slash = normalized.lastIndexOf('/');
        if (slash <= 0) return normalized;
        int parentSlash = normalized.lastIndexOf('/', slash - 1);
        return parentSlash >= 0 ? normalized.substring(parentSlash + 1) : normalized;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }
}
