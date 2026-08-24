package com.myhomelibcorp.application.navigation;

import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.exchange.ReadingHistoryPort;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.NavigationFacetRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.application.query.book.BookQuery;
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
    private final SeriesRepository seriesRepository;
    private final GenreRepository genreRepository;
    private final BookQueryRepository bookQueryRepository;
    private final NavigationFacetRepository navigationFacetRepository;
    private final ReadingHistoryPort readingHistoryPort;
    private final ExecutorPort executorPort;

    public DefaultNavigationQueryService(
            AuthorRepository authorRepository,
            SeriesRepository seriesRepository,
            GenreRepository genreRepository,
            BookQueryRepository bookQueryRepository,
            NavigationFacetRepository navigationFacetRepository,
            ReadingHistoryPort readingHistoryPort,
            ExecutorPort executorPort) {
        this.authorRepository = authorRepository;
        this.seriesRepository = seriesRepository;
        this.genreRepository = genreRepository;
        this.bookQueryRepository = bookQueryRepository;
        this.navigationFacetRepository = navigationFacetRepository;
        this.readingHistoryPort = readingHistoryPort;
        this.executorPort = executorPort;
    }

    @Override
    public java.util.concurrent.CompletableFuture<List<NavigationNodeDto>> load(NavigationMode mode) {
        if (mode == null) {
            return java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalArgumentException("Navigation mode cannot be null"));
        }
        return executorPort.submit(() -> switch (mode) {
            case AUTHORS -> loadAuthors();
            case SERIES -> loadSeries();
            case GENRES -> loadGenres();
            case YEARS -> loadYears();
            case LANGUAGES -> loadLanguages();
            case ARCHIVES -> loadArchives();
            case KEYWORDS -> loadKeywords();
            case GROUPS -> loadGroups();
            case REVIEWS -> loadReviews();
            case ALREADY_READ -> loadAlreadyRead();
            case HISTORY -> loadHistory();
            case ALL_BOOKS -> loadAllBooks();
        });
    }

    private List<NavigationNodeDto> loadAuthors() {
        return authorRepository.findAll().stream()
                .map(author -> NavigationNodeDto.of(
                        NavigationMode.AUTHORS,
                        author.getId().asString(),
                        author.getFullName()))
                .filter(DefaultNavigationQueryService::hasLabel)
                .sorted(LABEL_ORDER)
                .toList();
    }

    private List<NavigationNodeDto> loadSeries() {
        return seriesRepository.findAll().stream()
                .map(series -> NavigationNodeDto.of(
                        NavigationMode.SERIES,
                        series.getId().asString(),
                        series.getName()))
                .filter(DefaultNavigationQueryService::hasLabel)
                .sorted(LABEL_ORDER)
                .toList();
    }

    private List<NavigationNodeDto> loadGenres() {
        return genreRepository.findAll().stream()
                .map(genre -> NavigationNodeDto.of(
                        NavigationMode.GENRES,
                        genre.getId().asString(),
                        genre.getName()))
                .filter(DefaultNavigationQueryService::hasLabel)
                .sorted(LABEL_ORDER)
                .toList();
    }

    private List<NavigationNodeDto> loadYears() {
        return navigationFacetRepository.findYears().stream()
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

    private List<NavigationNodeDto> loadLanguages() {
        return navigationFacetRepository.findLanguages().stream()
                .map(DefaultNavigationQueryService::normalizeLanguageFacet)
                .filter(java.util.Objects::nonNull)
                .sorted(LABEL_ORDER)
                .toList();
    }

    private List<NavigationNodeDto> loadArchives() {
        List<NavigationFacetRepository.ArchiveFacet> facets = navigationFacetRepository.findArchives();
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

    private List<NavigationNodeDto> loadKeywords() {
        return navigationFacetRepository.findKeywords().stream()
                .map(facet -> new NavigationNodeDto(
                        NavigationMode.KEYWORDS, facet.id(), facet.label(), facet.bookCount()))
                .filter(DefaultNavigationQueryService::hasLabel)
                .sorted(LABEL_ORDER)
                .toList();
    }

    private List<NavigationNodeDto> loadGroups() {
        return navigationFacetRepository.findGroups().stream()
                .map(facet -> new NavigationNodeDto(
                        NavigationMode.GROUPS, facet.id(), facet.label(), facet.bookCount()))
                .filter(DefaultNavigationQueryService::hasLabel)
                .sorted(LABEL_ORDER)
                .toList();
    }

    private List<NavigationNodeDto> loadReviews() {
        Map<String, Integer> order = Map.of(
                ReviewNavigationFilter.RATED.id(), 0,
                ReviewNavigationFilter.REVIEWED.id(), 1,
                ReviewNavigationFilter.RATED_AND_REVIEWED.id(), 2);
        return navigationFacetRepository.findReviewSubsets().stream()
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


    private List<NavigationNodeDto> loadAlreadyRead() {
        long count = bookQueryRepository.count(BookQuery.builder().onlyRead(true).build());
        return List.of(new NavigationNodeDto(
                NavigationMode.ALREADY_READ,
                "already-read",
                "already-read",
                count));
    }

    private List<NavigationNodeDto> loadHistory() {
        return List.of(new NavigationNodeDto(
                NavigationMode.HISTORY,
                "history",
                "history",
                readingHistoryPort.count()));
    }

    private List<NavigationNodeDto> loadAllBooks() {
        long count = bookQueryRepository.count(BookQuery.builder().build());
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
