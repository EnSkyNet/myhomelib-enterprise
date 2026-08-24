package com.myhomelibcorp.application.navigation;

import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.exchange.ReadingHistoryPort;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.application.port.out.repository.NavigationFacetRepository;
import com.myhomelibcorp.application.port.out.repository.SeriesRepository;
import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DefaultNavigationQueryServiceTest {

    private AuthorRepository authorRepository;
    private SeriesRepository seriesRepository;
    private GenreRepository genreRepository;
    private BookQueryRepository bookQueryRepository;
    private NavigationFacetRepository navigationFacetRepository;
    private ReadingHistoryPort readingHistoryPort;
    private DefaultNavigationQueryService service;

    @BeforeEach
    void setUp() {
        authorRepository = mock(AuthorRepository.class);
        seriesRepository = mock(SeriesRepository.class);
        genreRepository = mock(GenreRepository.class);
        bookQueryRepository = mock(BookQueryRepository.class);
        navigationFacetRepository = mock(NavigationFacetRepository.class);
        readingHistoryPort = mock(ReadingHistoryPort.class);
        service = new DefaultNavigationQueryService(
                authorRepository,
                seriesRepository,
                genreRepository,
                bookQueryRepository,
                navigationFacetRepository,
                readingHistoryPort,
                directExecutor());
    }

    @Test
    void authorsAreReturnedAsStableSortedNodes() {
        AuthorId zId = AuthorId.generate();
        AuthorId aId = AuthorId.generate();
        when(authorRepository.findAll()).thenReturn(List.of(
                new Author(zId, "Zed", null, "Zulu"),
                new Author(aId, "Ann", null, "Alpha")));

        List<NavigationNodeDto> nodes = service.load(NavigationMode.AUTHORS).join();

        assertThat(nodes).extracting(NavigationNodeDto::label)
                .containsExactly("Alpha Ann", "Zulu Zed");
        assertThat(nodes.getFirst().id()).isEqualTo(aId.asString());
        assertThat(nodes).allMatch(node -> node.mode() == NavigationMode.AUTHORS);
    }

    @Test
    void seriesUseRepositoryIdsInsteadOfUiGeneratedIds() {
        SeriesId stableId = SeriesId.generate();
        when(seriesRepository.findAll()).thenReturn(List.of(
                new Series(stableId, "Chronicles", null)));

        List<NavigationNodeDto> nodes = service.load(NavigationMode.SERIES).join();

        assertThat(nodes).hasSize(1);
        assertThat(nodes.getFirst().id()).isEqualTo(stableId.asString());
        assertThat(nodes.getFirst().label()).isEqualTo("Chronicles");
    }

    @Test
    void genresUseStableGenreCodesAndAreSorted() {
        when(genreRepository.findAll()).thenReturn(List.of(
                new Genre("z_genre", "Zulu"),
                new Genre("a_genre", "Alpha")));

        List<NavigationNodeDto> nodes = service.load(NavigationMode.GENRES).join();

        assertThat(nodes).extracting(NavigationNodeDto::id)
                .containsExactly("a_genre", "z_genre");
    }

    @Test
    void yearsAreCountedAndSortedNewestFirst() {
        when(navigationFacetRepository.findYears()).thenReturn(List.of(
                new NavigationFacetRepository.Facet("1999", "1999", 3),
                new NavigationFacetRepository.Facet("2024", "2024", 8)));

        List<NavigationNodeDto> nodes = service.load(NavigationMode.YEARS).join();

        assertThat(nodes).extracting(NavigationNodeDto::id).containsExactly("2024", "1999");
        assertThat(nodes).extracting(NavigationNodeDto::bookCount).containsExactly(8L, 3L);
    }

    @Test
    void languagesAreNormalizedAndInvalidCodesAreIgnored() {
        when(navigationFacetRepository.findLanguages()).thenReturn(List.of(
                new NavigationFacetRepository.Facet("EN-us", "EN-us", 2),
                new NavigationFacetRepository.Facet("not_a_language", "not_a_language", 7),
                new NavigationFacetRepository.Facet("uk", "uk", 5)));

        List<NavigationNodeDto> nodes = service.load(NavigationMode.LANGUAGES).join();

        assertThat(nodes).extracting(NavigationNodeDto::id).containsExactly("en-US", "uk");
        assertThat(nodes).allMatch(node -> node.mode() == NavigationMode.LANGUAGES);
    }

    @Test
    void archivesUseStableCompositeKeysAndDisambiguateDuplicateFileNames() {
        when(navigationFacetRepository.findArchives()).thenReturn(List.of(
                new NavigationFacetRepository.ArchiveFacet("/lib-a", "/lib-a/2024/books.zip", 10),
                new NavigationFacetRepository.ArchiveFacet("/lib-b", "/lib-b/archive/books.zip", 4),
                new NavigationFacetRepository.ArchiveFacet("/lib-a", "/lib-a/solo.7z", 1)));

        List<NavigationNodeDto> nodes = service.load(NavigationMode.ARCHIVES).join();

        assertThat(nodes).hasSize(3);
        assertThat(nodes).extracting(NavigationNodeDto::label)
                .contains("2024/books.zip", "archive/books.zip", "solo.7z");
        NavigationNodeDto solo = nodes.stream().filter(n -> n.label().equals("solo.7z")).findFirst().orElseThrow();
        assertThat(ArchiveNavigationKey.decode(solo.id()))
                .isEqualTo(new ArchiveNavigationKey("/lib-a", "/lib-a/solo.7z"));
        assertThat(solo.bookCount()).isEqualTo(1);
    }

    @Test
    void keywordsAreReturnedWithCountsAndStableLowercaseIds() {
        when(navigationFacetRepository.findKeywords()).thenReturn(List.of(
                new NavigationFacetRepository.Facet("space", "Space", 4),
                new NavigationFacetRepository.Facet("ai", "AI", 2)));

        List<NavigationNodeDto> nodes = service.load(NavigationMode.KEYWORDS).join();

        assertThat(nodes).extracting(NavigationNodeDto::id).containsExactly("ai", "space");
        assertThat(nodes).extracting(NavigationNodeDto::bookCount).containsExactly(2L, 4L);
    }

    @Test
    void groupsIncludeEmptyGroupsAndUsePersistentIds() {
        when(navigationFacetRepository.findGroups()).thenReturn(List.of(
                new NavigationFacetRepository.Facet("2", "To Read", 0),
                new NavigationFacetRepository.Facet("1", "Favorites", 7)));

        List<NavigationNodeDto> nodes = service.load(NavigationMode.GROUPS).join();

        assertThat(nodes).extracting(NavigationNodeDto::label).containsExactly("Favorites", "To Read");
        assertThat(nodes).extracting(NavigationNodeDto::id).containsExactly("1", "2");
    }

    @Test
    void reviewSubsetsUseStableSemanticOrderAndIgnoreUnknownIds() {
        when(navigationFacetRepository.findReviewSubsets()).thenReturn(List.of(
                new NavigationFacetRepository.Facet("reviewed", "reviewed", 6),
                new NavigationFacetRepository.Facet("unknown", "unknown", 99),
                new NavigationFacetRepository.Facet("rated-reviewed", "rated-reviewed", 3),
                new NavigationFacetRepository.Facet("rated", "rated", 8)));

        List<NavigationNodeDto> nodes = service.load(NavigationMode.REVIEWS).join();

        assertThat(nodes).extracting(NavigationNodeDto::id)
                .containsExactly("rated", "reviewed", "rated-reviewed");
        assertThat(nodes).extracting(NavigationNodeDto::bookCount).containsExactly(8L, 6L, 3L);
    }


    @Test
    void alreadyReadIsSyntheticNodeWithExactReadCount() {
        when(bookQueryRepository.count(any())).thenReturn(42L);

        List<NavigationNodeDto> nodes = service.load(NavigationMode.ALREADY_READ).join();

        assertThat(nodes).containsExactly(
                new NavigationNodeDto(NavigationMode.ALREADY_READ, "already-read", "already-read", 42L));
    }

    @Test
    void historyIsSyntheticNodeWithPersistentHistoryCount() {
        when(readingHistoryPort.count()).thenReturn(17L);

        List<NavigationNodeDto> nodes = service.load(NavigationMode.HISTORY).join();

        assertThat(nodes).containsExactly(
                new NavigationNodeDto(NavigationMode.HISTORY, "history", "history", 17L));
    }

    @Test
    void allBooksIsSyntheticNodeWithExactCatalogueCount() {
        when(bookQueryRepository.count(any())).thenReturn(1234L);

        List<NavigationNodeDto> nodes = service.load(NavigationMode.ALL_BOOKS).join();

        assertThat(nodes).containsExactly(
                new NavigationNodeDto(NavigationMode.ALL_BOOKS, "all", "all-books", 1234L));
    }

    private static ExecutorPort directExecutor() {
        return new ExecutorPort() {
            @Override
            public <T> CompletableFuture<T> submit(Callable<T> task) {
                try {
                    return CompletableFuture.completedFuture(task.call());
                } catch (Exception e) {
                    return CompletableFuture.failedFuture(e);
                }
            }

            @Override
            public void execute(Runnable task) {
                task.run();
            }
        };
    }
}
