package com.myhomelibcorp.e2e;

import com.myhomelibcorp.application.annotation.AnnotationAnchorData;
import com.myhomelibcorp.application.annotation.AnnotationManagerFilter;
import com.myhomelibcorp.application.annotation.AnnotationManagerService;
import com.myhomelibcorp.application.annotation.AnnotationReaderResolver;
import com.myhomelibcorp.application.annotation.AnnotationService;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.config.DataSourceConfig;
import com.myhomelibcorp.infrastructure.persistence.QueryExecutor;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteAnnotationManagerQueryAdapter;
import com.myhomelibcorp.infrastructure.persistence.sqlite.SqliteAnnotationRepository;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import javax.sql.DataSource;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Persistent annotation journey covering the non-visual half of the Reader workflow:
 * create -> reopen -> project/resolve -> edit -> batch delete -> undo -> reopen.
 * JavaFX activation/hit-testing is covered independently in the reader/UI test modules.
 */
class AnnotationLifecycleJourneyE2ETest {
    @TempDir Path tempDir;

    @Test
    void noteLifecycleSurvivesRepositoryReopenAndUndo() throws Exception {
        Path db = tempDir.resolve("annotation-lifecycle.db");
        DataSource initialDs = new DriverManagerDataSource("jdbc:sqlite:" + db.toAbsolutePath());
        Flyway.configure().dataSource(initialDs).locations("classpath:db/migration").load().migrate();
        Fixture initial = fixture(initialDs);
        initial.jdbc().update("INSERT INTO books(id,title,file_name,deleted,local) VALUES('book-1','Книга','book.fb2',0,1)");
        initial.jdbc().update("""
                INSERT INTO book_artifacts(artifact_id,book_id,artifact_name,file_name,local,remote,state)
                VALUES('artifact-1','book-1','book.fb2','book.fb2',1,0,'AVAILABLE')
                """);

        AnnotationService createService = new AnnotationService(initial.repository());
        AnnotationAnchorData anchor = new AnnotationAnchorData(
                "book-1", "artifact-1", "chapter-1", "Розділ 1", "p-2",
                7, 13, 0.35, "цитата", "Перед ", " після");
        var created = createService.createNote(anchor, "#FFF59D", "Перша нотатка", Set.of("сюжет", "важливо"));
        assertThat(created.id()).isNotBlank();

        // Reopen through independent repository/service instances to model an application restart.
        DataSource reopenedDs = new DriverManagerDataSource("jdbc:sqlite:" + db.toAbsolutePath());
        Fixture reopened = fixture(reopenedDs);
        AnnotationService reopenedService = new AnnotationService(reopened.repository());
        var readerItem = reopenedService.listBookAnnotationViews("book-1").getFirst();
        assertThat(readerItem.noteText()).isEqualTo("Перша нотатка");
        assertThat(readerItem.tags()).containsExactlyInAnyOrder("сюжет", "важливо");
        assertThat(AnnotationReaderResolver.resolve(readerItem, "artifact-1", "Перед цитата після"))
                .isPresent();

        AnnotationManagerService manager = new AnnotationManagerService(reopened.query(), reopened.repository());
        manager.update(created.id(), "Відредагована нотатка", "#80CBC4", Set.of("сюжет", "перевірено"));
        assertThat(reopened.repository().findById(created.id()).orElseThrow().note())
                .isEqualTo("Відредагована нотатка");

        var undo = manager.deleteForUndo(Set.of(created.id()));
        assertThat(reopened.repository().findById(created.id())).isEmpty();
        manager.restoreDeleted(undo);
        assertThat(reopened.repository().findById(created.id()).orElseThrow().tags())
                .containsExactlyInAnyOrder("сюжет", "перевірено");

        DataSource finalDs = new DriverManagerDataSource("jdbc:sqlite:" + db.toAbsolutePath());
        Fixture finalFixture = fixture(finalDs);
        var persisted = finalFixture.repository().findById(created.id()).orElseThrow();
        assertThat(persisted.note()).isEqualTo("Відредагована нотатка");
        assertThat(finalFixture.query().query(AnnotationManagerFilter.empty(), 0, 20).items())
                .extracting(item -> item.id())
                .contains(created.id());
    }

    private static Fixture fixture(DataSource dataSource) throws Exception {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        CollectionManager manager = new CollectionManager(jdbc, new DataSourceConfig());
        setAtomic(manager, "currentJdbcTemplate", jdbc);
        setAtomic(manager, "currentDataSource", dataSource);
        QueryExecutor queryExecutor = new QueryExecutor(manager);
        return new Fixture(
                jdbc,
                new SqliteAnnotationRepository(queryExecutor, manager),
                new SqliteAnnotationManagerQueryAdapter(queryExecutor, manager));
    }

    @SuppressWarnings("unchecked")
    private static void setAtomic(CollectionManager manager, String fieldName, Object value) throws Exception {
        Field field = CollectionManager.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        AtomicReference<Object> reference = (AtomicReference<Object>) field.get(manager);
        reference.set(value);
    }

    private record Fixture(
            JdbcTemplate jdbc,
            SqliteAnnotationRepository repository,
            SqliteAnnotationManagerQueryAdapter query
    ) { }
}
