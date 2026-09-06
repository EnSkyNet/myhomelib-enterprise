package com.myhomelibcorp.e2e;

import com.myhomelibcorp.application.port.out.repository.BookQueryRepository;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.book.BookSnapshot;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.importer.fb2.Fb2Importer;
import com.myhomelibcorp.infrastructure.search.LuceneSearchService;
import com.myhomelibcorp.infrastructure.search.LuceneCollectionIndexLifecycle;
import com.myhomelibcorp.reader.api.FileBookSource;
import com.myhomelibcorp.reader.api.PageDimensions;
import com.myhomelibcorp.reader.api.ReaderPosition;
import com.myhomelibcorp.reader.api.ReaderTheme;
import com.myhomelibcorp.reader.core.ReaderEngine;
import com.myhomelibcorp.reader.core.ReaderEngineBuilder;
import com.myhomelibcorp.reader.core.position.ReaderPositionManager;
import com.myhomelibcorp.reader.core.registry.DefaultBookFormatRegistry;
import com.myhomelibcorp.reader.format.fb2.Fb2Format;
import com.myhomelibcorp.reader.model.PageLayout;
import com.myhomelibcorp.reader.render.api.ReaderRenderer;
import com.myhomelibcorp.reader.render.api.RenderMetrics;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.queryparser.classic.MultiFieldQueryParser;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class ImportSearchReaderJourneyE2ETest {

    @TempDir
    Path tempDir;

    @AfterEach
    void cleanup() {
        System.clearProperty("myhomelib.dataDir");
    }

    @Test
    void fb2FileFlowsFromImporterThroughLuceneIntoReader() throws Exception {
        Path file = writeFb2("journey.fb2", "Київська подорож", "Іван", "Франко",
                "Подорож до Києва", "Перший розділ про Київ", "Другий розділ про Дніпро");

        Book book = new Fb2Importer().importBooks(file).findFirst().orElseThrow();
        assertThat(book.getTitle()).isEqualTo("Київська подорож");
        assertThat(book.authorsText()).contains("Франко");

        try (SearchHarness harness = searchService("journey-search")) {
            harness.search.indexSnapshot(BookSnapshot.fromBook(book));
            harness.search.commit();
            harness.lifecycle.markCurrentIndexSynchronized();

            var result = harness.search.search(SearchRequest.builder().text("Київська").build());
            assertThat(result.bookIds()).containsExactly(book.getId());
        }

        ReaderEngine reader = readerEngine(new ReaderPositionManager());
        reader.open(new FileBookSource(file, book.getId().asString()));
        assertThat(reader.isOpen()).isTrue();
        assertThat(reader.getCurrentDocument().metadata().title()).isEqualTo("Київська подорож");
        assertThat(reader.getCurrentDocument().totalTextLength()).isGreaterThan(20);
        assertThat(reader.getCurrentDocument().chapters()).isNotEmpty();
        reader.close();
    }

    @Test
    void importedMetadataRemainsSearchableByAuthorAndLanguageAliases() throws Exception {
        Path file = writeFb2("aliases.fb2", "Львівські історії", "Леся", "Українка",
                "Оповідання", "Текст зі Львова", "Продовження тексту");
        Book book = new Fb2Importer().importBooks(file).findFirst().orElseThrow();

        try (SearchHarness harness = searchService("alias-search")) {
            harness.search.indexSnapshot(BookSnapshot.fromBook(book));
            harness.search.commit();
            harness.lifecycle.markCurrentIndexSynchronized();

            assertThat(harness.search.search(SearchRequest.builder().text("author:Українка").build()).bookIds())
                    .containsExactly(book.getId());
            assertThat(harness.search.search(SearchRequest.builder().text("lang:uk").build()).bookIds())
                    .containsExactly(book.getId());
        }
    }

    @Test
    void readerRestoresPositionAfterCloseAndReopen() throws Exception {
        Path file = writeFb2("position.fb2", "Довга книга", "Автор", "Тестовий",
                "Навігація", longText("Перший", 90), longText("Другий", 90));
        ReaderPositionManager positions = new ReaderPositionManager();
        ReaderEngine reader = readerEngine(positions);
        FileBookSource source = new FileBookSource(file, "stable-reader-id");

        reader.open(source);
        reader.goToPercent(63.0);
        long saved = reader.getCurrentPosition().textOffset();
        assertThat(saved).isGreaterThan(0);
        reader.close();

        reader.open(source);
        assertThat(reader.getCurrentPosition().textOffset()).isEqualTo(saved);
        assertThat(reader.getProgressPercent()).isGreaterThan(50.0);
        reader.close();
    }

    @Test
    void readerPageNavigationMovesForwardAndBackOnImportedFb2() throws Exception {
        Path file = writeFb2("navigation.fb2", "Навігаційна книга", "Олена", "Авторка",
                "Пагінація", longText("Абзац", 160), longText("Розділ", 120));
        ReaderEngine reader = readerEngine(new ReaderPositionManager());
        reader.open(new FileBookSource(file, "navigation-book"));
        PageDimensions dimensions = new PageDimensions(420, 320, 30, 30, 20, 20);

        long start = reader.getCurrentPosition().textOffset();
        PageLayout firstPage = reader.getCurrentPage(dimensions);
        assertThat(firstPage.isEmpty()).isFalse();

        reader.nextPage(dimensions);
        long forward = reader.getCurrentPosition().textOffset();
        assertThat(forward).isGreaterThan(start);

        reader.previousPage(dimensions);
        long back = reader.getCurrentPosition().textOffset();
        assertThat(back).isLessThan(forward);
        reader.close();
    }

    private SearchHarness searchService(String collectionId) {
        System.setProperty("myhomelib.dataDir", tempDir.resolve(collectionId + "-data").toString());
        StandardAnalyzer analyzer = new StandardAnalyzer();
        MultiFieldQueryParser parser = new MultiFieldQueryParser(
                new String[]{"title", "authors", "series", "genres", "keywords", "annotation", "file_name", "publisher"},
                analyzer);
        parser.setAllowLeadingWildcard(true);
        BookQueryRepository books = mock(BookQueryRepository.class);
        LuceneSearchService search = new LuceneSearchService(
                new ByteBuffersDirectory(), analyzer, parser, books);
        search.init();
        LuceneCollectionIndexLifecycle lifecycle = new LuceneCollectionIndexLifecycle(search, books);
        lifecycle.activateCollectionIndex(new Collection(
                collectionId, "E2E search collection", tempDir, tempDir.resolve(collectionId + ".db").toString(),
                0, null, null, null, null, null));
        return new SearchHarness(search, lifecycle);
    }

    private static final class SearchHarness implements AutoCloseable {
        private final LuceneSearchService search;
        private final LuceneCollectionIndexLifecycle lifecycle;

        private SearchHarness(LuceneSearchService search, LuceneCollectionIndexLifecycle lifecycle) {
            this.search = search;
            this.lifecycle = lifecycle;
        }

        @Override
        public void close() {
            search.close();
        }
    }

    private ReaderEngine readerEngine(ReaderPositionManager positions) {
        DefaultBookFormatRegistry registry = new DefaultBookFormatRegistry();
        registry.register(new Fb2Format());
        return new ReaderEngineBuilder()
                .formatRegistry(registry)
                .positionManager(positions)
                .renderer(new NoopRenderer())
                .build();
    }

    private Path writeFb2(String name, String title, String firstName, String lastName,
                          String annotation, String firstBody, String secondBody) throws Exception {
        Path file = tempDir.resolve(name);
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0">
                  <description>
                    <title-info>
                      <genre>sf</genre>
                      <author><first-name>%s</first-name><last-name>%s</last-name></author>
                      <book-title>%s</book-title>
                      <annotation><p>%s</p></annotation>
                      <keywords>e2e, smoke</keywords>
                      <lang>uk</lang>
                    </title-info>
                  </description>
                  <body>
                    <section><title><p>Розділ 1</p></title><p>%s</p></section>
                    <section><title><p>Розділ 2</p></title><p>%s</p></section>
                  </body>
                </FictionBook>
                """.formatted(firstName, lastName, title, annotation, firstBody, secondBody);
        Files.writeString(file, xml, StandardCharsets.UTF_8);
        return file;
    }

    private static String longText(String token, int repeats) {
        return (token + " текст для стабільного E2E сценарію читання та пагінації. ").repeat(repeats);
    }

    private static final class NoopRenderer implements ReaderRenderer {
        @Override
        public void renderPage(PageLayout page, ReaderTheme theme) { }

        @Override
        public RenderMetrics getMetrics() { return RenderMetrics.empty(); }

        @Override
        public void clear() { }
    }
}
