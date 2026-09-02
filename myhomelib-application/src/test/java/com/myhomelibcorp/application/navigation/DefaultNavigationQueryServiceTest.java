package com.myhomelibcorp.application.navigation;

import com.myhomelibcorp.application.catalog.CatalogUpdateService;
import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.filter.BookFilterStateService;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.application.port.out.repository.AuthorRepository;
import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.port.out.repository.NavigationFacetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class DefaultNavigationQueryServiceTest {

    private AuthorRepository authorRepository;
    private BookQueryRepository bookQueryRepository;
    private NavigationFacetRepository navigationFacetRepository;
    private BookFilterStateService filterStateService;
    private CatalogUpdateService catalogUpdateService;
    private DefaultNavigationQueryService service;
    private BookFilterSpec filter;

    @BeforeEach
    void setUp() {
        authorRepository = mock(AuthorRepository.class);
        bookQueryRepository = mock(BookQueryRepository.class);
        navigationFacetRepository = mock(NavigationFacetRepository.class);
        filterStateService = mock(BookFilterStateService.class);
        catalogUpdateService = mock(CatalogUpdateService.class);
        filter = BookFilterSpec.empty();
        when(filterStateService.current()).thenReturn(filter);
        service = new DefaultNavigationQueryService(
                authorRepository,
                bookQueryRepository,
                navigationFacetRepository,
                filterStateService,
                catalogUpdateService,
                directExecutor());
    }

    @Test
    void authorsAreLoadedOnlyForResolvedFilteredInitial() {
        when(navigationFacetRepository.findFirstAuthorInitial(filter)).thenReturn(java.util.Optional.of('А'));
        when(navigationFacetRepository.findAuthors('А', filter)).thenReturn(List.of(
                new NavigationFacetRepository.Facet("author-a", "Абрамов Андрій", 12)));

        List<NavigationNodeDto> nodes = service.load(NavigationMode.AUTHORS).join();

        assertThat(nodes).containsExactly(
                new NavigationNodeDto(NavigationMode.AUTHORS, "author-a", "Абрамов Андрій", 12));
        verify(navigationFacetRepository).findFirstAuthorInitial(filter);
        verify(navigationFacetRepository).findAuthors('А', filter);
        verify(authorRepository, never()).findAll();
    }

    @Test
    void explicitAuthorInitialDoesNotResolveOrLoadAllAuthors() {
        when(navigationFacetRepository.findAuthors('Б', filter)).thenReturn(List.of(
                new NavigationFacetRepository.Facet("author-b", "Бабенко Богдан", 3)));

        List<NavigationNodeDto> nodes = service.load(NavigationMode.AUTHORS, 'Б').join();

        assertThat(nodes).extracting(NavigationNodeDto::label).containsExactly("Бабенко Богдан");
        verify(navigationFacetRepository).findAuthors('Б', filter);
        verify(navigationFacetRepository, never()).findFirstAuthorInitial(filter);
        verify(authorRepository, never()).findAll();
    }

    @Test
    void authorPageKeepsExactTotalWithoutMaterializingWholeInitial() {
        when(navigationFacetRepository.findAuthorsPage('А', filter, 500, 500)).thenReturn(
                new NavigationFacetRepository.FacetPage(List.of(
                        new NavigationFacetRepository.Facet("author-501", "Андрухович Юрій", 4)), 12421));

        var page = service.loadAuthorsPage('А', 500, 500).join();

        assertThat(page.totalElements()).isEqualTo(12421);
        assertThat(page.currentPage()).isEqualTo(1);
        assertThat(page.content()).extracting(NavigationNodeDto::id).containsExactly("author-501");
        verify(navigationFacetRepository).findAuthorsPage('А', filter, 500, 500);
        verify(navigationFacetRepository, never()).findAuthors('А', filter);
    }

    @Test
    void authorSearchUsesFilteredFacetRepositoryAndIsBounded() {
        when(navigationFacetRepository.searchAuthors("алек", filter, 200)).thenReturn(List.of(
                new NavigationFacetRepository.Facet("a", "Александров", 8)));

        assertThat(service.searchAuthors("алек", 200).join())
                .extracting(NavigationNodeDto::label).containsExactly("Александров");
        verify(navigationFacetRepository).searchAuthors("алек", filter, 200);
    }

    @Test
    void seriesAndGenresUseFilteredFacetCountsAndStableIds() {
        when(navigationFacetRepository.findSeries(filter)).thenReturn(List.of(
                new NavigationFacetRepository.Facet("series-2", "Zulu", 2),
                new NavigationFacetRepository.Facet("series-1", "Alpha", 7)));
        when(navigationFacetRepository.findGenres(filter)).thenReturn(List.of(
                new NavigationFacetRepository.Facet("z_genre", "Zulu", 4),
                new NavigationFacetRepository.Facet("a_genre", "Alpha", 9)));

        assertThat(service.load(NavigationMode.SERIES).join())
                .extracting(NavigationNodeDto::id).containsExactly("series-1", "series-2");
        assertThat(service.load(NavigationMode.GENRES).join())
                .extracting(NavigationNodeDto::id).containsExactly("a_genre", "z_genre");
    }

    @Test
    void yearsAreCountedAndSortedNewestFirst() {
        when(navigationFacetRepository.findYears(filter)).thenReturn(List.of(
                new NavigationFacetRepository.Facet("1999", "1999", 3),
                new NavigationFacetRepository.Facet("2024", "2024", 8)));

        List<NavigationNodeDto> nodes = service.load(NavigationMode.YEARS).join();

        assertThat(nodes).extracting(NavigationNodeDto::id).containsExactly("2024", "1999");
        assertThat(nodes).extracting(NavigationNodeDto::bookCount).containsExactly(8L, 3L);
    }

    @Test
    void languagesAreNormalizedAndInvalidCodesAreIgnored() {
        when(navigationFacetRepository.findLanguages(filter)).thenReturn(List.of(
                new NavigationFacetRepository.Facet("EN-us", "EN-us", 2),
                new NavigationFacetRepository.Facet("not_a_language", "not_a_language", 7),
                new NavigationFacetRepository.Facet("uk", "uk", 5)));

        List<NavigationNodeDto> nodes = service.load(NavigationMode.LANGUAGES).join();

        assertThat(nodes).extracting(NavigationNodeDto::id).containsExactly("en-US", "uk");
    }

    @Test
    void archivesUseStableCompositeKeysAndDisambiguateDuplicateFileNames() {
        when(navigationFacetRepository.findArchives(filter)).thenReturn(List.of(
                new NavigationFacetRepository.ArchiveFacet("/lib-a", "/lib-a/2024/books.zip", 10),
                new NavigationFacetRepository.ArchiveFacet("/lib-b", "/lib-b/archive/books.zip", 4),
                new NavigationFacetRepository.ArchiveFacet("/lib-a", "/lib-a/solo.7z", 1)));

        List<NavigationNodeDto> nodes = service.load(NavigationMode.ARCHIVES).join();

        assertThat(nodes).extracting(NavigationNodeDto::label)
                .contains("2024/books.zip", "archive/books.zip", "solo.7z");
    }

    @Test
    void keywordsGroupsAndReviewsUseSameFilter() {
        when(navigationFacetRepository.findKeywords(filter)).thenReturn(List.of(
                new NavigationFacetRepository.Facet("space", "Space", 4)));
        when(navigationFacetRepository.findGroups(filter)).thenReturn(List.of(
                new NavigationFacetRepository.Facet("1", "Favorites", 7)));
        when(navigationFacetRepository.findReviewSubsets(filter)).thenReturn(List.of(
                new NavigationFacetRepository.Facet("reviewed", "reviewed", 6),
                new NavigationFacetRepository.Facet("rated-reviewed", "rated-reviewed", 3),
                new NavigationFacetRepository.Facet("rated", "rated", 8)));

        assertThat(service.load(NavigationMode.KEYWORDS).join().getFirst().bookCount()).isEqualTo(4);
        assertThat(service.load(NavigationMode.GROUPS).join().getFirst().bookCount()).isEqualTo(7);
        assertThat(service.load(NavigationMode.REVIEWS).join()).extracting(NavigationNodeDto::id)
                .containsExactly("rated", "reviewed", "rated-reviewed");
    }

    @Test
    void updatesRemainIndependentFromBookFilter() {
        when(catalogUpdateService.pendingUpdateCount()).thenReturn(27L);
        assertThat(service.load(NavigationMode.UPDATES).join()).containsExactly(
                new NavigationNodeDto(NavigationMode.UPDATES, "updates", "Оновлення", 27L));
    }

    @Test
    void syntheticBookModesCountThroughFilteredBookQuery() {
        when(bookQueryRepository.count(any())).thenReturn(42L, 17L, 1234L);

        assertThat(service.load(NavigationMode.ALREADY_READ).join().getFirst().bookCount()).isEqualTo(42L);
        assertThat(service.load(NavigationMode.HISTORY).join().getFirst().bookCount()).isEqualTo(17L);
        assertThat(service.load(NavigationMode.ALL_BOOKS).join().getFirst().bookCount()).isEqualTo(1234L);
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
