package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.readinglist.ReadingList;
import com.myhomelibcorp.domain.model.readinglist.ReadingListId;
import com.myhomelibcorp.domain.model.readinglist.ReadingListItem;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SqliteReadingListRepositoryTest {
    @TempDir
    Path tempDir;
    private HikariDataSource dataSource;
    private JdbcTemplate jdbc;
    private SqliteReadingListRepository repository;

    @BeforeEach
    void setUp() {
        dataSource = new HikariDataSource();
        dataSource.setJdbcUrl("jdbc:sqlite:file:reading-lists-" + UUID.randomUUID() + "?mode=memory&cache=shared");
        dataSource.setDriverClassName("org.sqlite.JDBC");
        dataSource.setMaximumPoolSize(3);
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load().migrate();
        jdbc = new JdbcTemplate(dataSource);
        TestCollectionManager manager = new TestCollectionManager(jdbc);
        manager.setCurrentCollection(new Collection("c1", "Test", Path.of("."), null, 1, null, null, null, null));
        manager.setCurrentDataSource(dataSource);
        manager.setCurrentJdbcTemplate(jdbc);
        repository = new SqliteReadingListRepository(manager);
    }

    @AfterEach
    void tearDown() {
        if (dataSource != null) dataSource.close();
    }

    @Test
    void createListRenameListListAndDuplicateNamePolicyAreDeterministic() {
        ReadingList first = repository.save(ReadingList.create("  Queue  ", "First"));
        ReadingList second = repository.save(ReadingList.create("Later", "Second"));

        ReadingList renamed = repository.save(first.rename("Queue Renamed"));

        assertThat(renamed.name()).isEqualTo("Queue Renamed");
        assertThat(repository.findByName(" queue renamed ")).map(ReadingList::id).contains(first.id());
        assertThat(repository.findAll()).extracting(ReadingList::name)
                .containsExactly("Later", "Queue Renamed");
        assertThat(repository.itemCounts()).isEmpty();
        assertThatThrownBy(() -> repository.save(second.rename(" QUEUE RENAMED ")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    @Test
    void addOneManyAndRepeatedBookKeepsUniqueContiguousMembership() {
        ReadingList list = repository.save(ReadingList.create("Queue", ""));
        BookId b1 = insertBook(1, "One");
        BookId b2 = insertBook(2, "Two");
        BookId b3 = insertBook(3, "Three");

        repository.addBooks(list.id(), List.of(b1));
        repository.addBooks(list.id(), List.of(b2, b3, b2));
        repository.addBooks(list.id(), List.of(b1, b3));

        assertThat(repository.findItems(list.id())).extracting(item -> item.bookId().asString())
                .containsExactly(b1.asString(), b2.asString(), b3.asString());
        assertThat(repository.findItems(list.id())).extracting("position").containsExactly(0, 1, 2);
        assertThat(repository.itemCounts()).containsEntry(list.id(), 3);
    }

    @Test
    void reorderIsDeterministicAndInvalidMembershipDoesNotMutate() {
        ReadingList list = repository.save(ReadingList.create("Queue", ""));
        BookId b1 = insertBook(1, "One");
        BookId b2 = insertBook(2, "Two");
        BookId b3 = insertBook(3, "Three");
        repository.addBooks(list.id(), List.of(b1, b2, b3));

        repository.reorder(list.id(), List.of(b3, b1, b2));
        assertThat(ids(list.id())).containsExactly(b3, b1, b2);

        assertThatThrownBy(() -> repository.reorder(list.id(), List.of(b3, b1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> repository.reorder(list.id(), List.of(b3, b1, b1)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(ids(list.id())).containsExactly(b3, b1, b2);
    }

    @Test
    void removeAndSoftDeleteCompactPositionsWithoutCorruptingOtherFeatures() {
        ReadingList list = repository.save(ReadingList.create("Queue", ""));
        BookId b1 = insertBook(1, "One");
        BookId b2 = insertBook(2, "Two");
        BookId b3 = insertBook(3, "Three");
        jdbc.update("INSERT INTO groups(name,allow_delete) VALUES('G',1)");
        long groupId = jdbc.queryForObject("SELECT id FROM groups WHERE name='G'", Long.class);
        jdbc.update("INSERT INTO book_groups(book_id,group_id) VALUES(?,?)", b1.asString(), groupId);
        jdbc.update("INSERT INTO saved_searches(id,name,query,filters,created_at,last_used,use_count) VALUES('s1','S','','{}',CURRENT_TIMESTAMP,CURRENT_TIMESTAMP,0)");
        repository.addBooks(list.id(), List.of(b1, b2, b3));

        repository.removeBooks(list.id(), List.of(b2));
        assertThat(ids(list.id())).containsExactly(b1, b3);
        assertThat(repository.findItems(list.id())).extracting("position").containsExactly(0, 1);

        jdbc.update("UPDATE books SET deleted=1 WHERE id=?", b1.asString());
        assertThat(ids(list.id())).containsExactly(b3);
        assertThat(repository.findItems(list.id())).extracting("position").containsExactly(0);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM book_groups WHERE group_id=?", Integer.class, groupId)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM saved_searches WHERE id='s1'", Integer.class)).isEqualTo(1);
    }

    @Test
    void deleteListDeletesOnlyMembershipAndLeavesBookRecordAndFileMetadata() {
        ReadingList list = repository.save(ReadingList.create("Queue", ""));
        BookId book = insertBook(1, "One");
        repository.addBooks(list.id(), List.of(book));

        repository.delete(list.id());

        assertThat(repository.findById(list.id())).isEmpty();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM reading_list_items WHERE list_id=?", Integer.class, list.id().value())).isZero();
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM books WHERE id=? AND file_name='1.fb2'", Integer.class, book.asString())).isEqualTo(1);
    }

    @Test
    void emptyListAndResumeUseManualOrderAndCurrentProgress() {
        ReadingList list = repository.save(ReadingList.create("Queue", ""));
        assertThat(repository.findItems(list.id())).isEmpty();
        assertThat(repository.findResumeBookId(list.id())).isEmpty();

        BookId completed = insertBook(1, "Done");
        BookId active = insertBook(2, "Active");
        BookId unread = insertBook(3, "Unread");
        repository.addBooks(list.id(), List.of(completed, active, unread));
        jdbc.update("UPDATE books SET progress=100 WHERE id=?", completed.asString());
        jdbc.update("INSERT INTO reading_progress(book_id,paragraph_id,char_offset,percent,updated_at) VALUES(?, '',0,35,CURRENT_TIMESTAMP)", active.asString());

        assertThat(repository.findResumeBookId(list.id())).contains(active);
        jdbc.update("UPDATE reading_progress SET percent=100 WHERE book_id=?", active.asString());
        assertThat(repository.findResumeBookId(list.id())).contains(unread);
    }

    @Test
    void membershipAndOrderSurviveDatabaseRestart() {
        Path database = tempDir.resolve("reading-lists-restart.db");
        ReadingListId listId;
        BookId first = bookId(11);
        BookId second = bookId(12);

        HikariDataSource firstDataSource = new HikariDataSource();
        firstDataSource.setJdbcUrl("jdbc:sqlite:" + database);
        firstDataSource.setDriverClassName("org.sqlite.JDBC");
        firstDataSource.setMaximumPoolSize(2);
        Flyway.configure().dataSource(firstDataSource).locations("classpath:db/migration").load().migrate();
        JdbcTemplate firstJdbc = new JdbcTemplate(firstDataSource);
        TestCollectionManager firstManager = new TestCollectionManager(firstJdbc);
        firstManager.setCurrentCollection(new Collection("restart", "Restart", database.getParent(), null, 1, null, null, null, null));
        firstManager.setCurrentDataSource(firstDataSource);
        firstManager.setCurrentJdbcTemplate(firstJdbc);
        SqliteReadingListRepository firstRepository = new SqliteReadingListRepository(firstManager);
        firstJdbc.update("INSERT INTO books(id,title,file_name,local) VALUES(?,?,?,1)", first.asString(), "First", "first.fb2");
        firstJdbc.update("INSERT INTO books(id,title,file_name,local) VALUES(?,?,?,1)", second.asString(), "Second", "second.fb2");
        ReadingList created = firstRepository.save(ReadingList.create("Restart Queue", ""));
        listId = created.id();
        firstRepository.addBooks(listId, List.of(first, second));
        firstRepository.reorder(listId, List.of(second, first));
        firstDataSource.close();

        HikariDataSource reopenedDataSource = new HikariDataSource();
        reopenedDataSource.setJdbcUrl("jdbc:sqlite:" + database);
        reopenedDataSource.setDriverClassName("org.sqlite.JDBC");
        reopenedDataSource.setMaximumPoolSize(2);
        JdbcTemplate reopenedJdbc = new JdbcTemplate(reopenedDataSource);
        TestCollectionManager reopenedManager = new TestCollectionManager(reopenedJdbc);
        reopenedManager.setCurrentCollection(new Collection("restart", "Restart", database.getParent(), null, 1, null, null, null, null));
        reopenedManager.setCurrentDataSource(reopenedDataSource);
        reopenedManager.setCurrentJdbcTemplate(reopenedJdbc);
        SqliteReadingListRepository reopenedRepository = new SqliteReadingListRepository(reopenedManager);
        try {
            assertThat(reopenedRepository.findById(listId)).isPresent();
            assertThat(reopenedRepository.findItems(listId)).extracting(ReadingListItem::bookId)
                    .containsExactly(second, first);
            assertThat(reopenedRepository.findItems(listId)).extracting(ReadingListItem::position)
                    .containsExactly(0, 1);
        } finally {
            reopenedDataSource.close();
        }
    }

    @Test
    void reorderOfMoreThanOneThousandItemsRemainsContiguous() {
        ReadingList list = repository.save(ReadingList.create("Large", ""));
        List<BookId> books = new ArrayList<>(1001);
        List<Object[]> rows = new ArrayList<>(1001);
        for (int i = 0; i < 1001; i++) {
            BookId id = bookId(i + 1);
            books.add(id);
            rows.add(new Object[]{id.asString(), "Book " + i, i + ".fb2"});
        }
        jdbc.batchUpdate("INSERT INTO books(id,title,file_name,local) VALUES(?,?,?,1)", rows);
        repository.addBooks(list.id(), books);
        List<BookId> reversed = new ArrayList<>(books);
        Collections.reverse(reversed);

        repository.reorder(list.id(), reversed);

        assertThat(ids(list.id())).containsExactlyElementsOf(reversed);
        assertThat(repository.findItems(list.id())).extracting("position")
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, 1001).boxed().toList());
    }

    private List<BookId> ids(ReadingListId id) {
        return repository.findItems(id).stream().map(item -> item.bookId()).toList();
    }

    private BookId insertBook(int n, String title) {
        BookId id = bookId(n);
        jdbc.update("INSERT INTO books(id,title,file_name,local) VALUES(?,?,?,1)", id.asString(), title, n + ".fb2");
        return id;
    }

    private static BookId bookId(int n) {
        return BookId.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }
}
