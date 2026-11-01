package com.myhomelibcorp.infrastructure.persistence.sqlite.helper;

import com.myhomelibcorp.application.port.out.repository.GenreRepository;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.infrastructure.persistence.mapper.GenreRowMapper;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

import java.sql.Connection;
import java.sql.DriverManager;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BookGenreHelperTest {

    @Test
    void loadsParentCategoryNameWithBookGenreFromSameCollectionDatabase() throws Exception {
        try (Connection connection = DriverManager.getConnection("jdbc:sqlite::memory:")) {
            JdbcTemplate jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            jdbc.execute("""
                    CREATE TABLE genres (
                        code TEXT PRIMARY KEY,
                        name TEXT,
                        parent_code TEXT,
                        fb2_code TEXT
                    )
                    """);
            jdbc.execute("""
                    CREATE TABLE book_genres (
                        book_id TEXT NOT NULL,
                        genre_code TEXT NOT NULL,
                        PRIMARY KEY (book_id, genre_code)
                    )
                    """);
            jdbc.update("INSERT INTO genres(code,name,parent_code,fb2_code) VALUES (?,?,NULL,NULL)",
                    "literature", "Література");
            jdbc.update("INSERT INTO genres(code,name,parent_code,fb2_code) VALUES (?,?,?,?)",
                    "fiction", "Фантастика", "literature", "sf");
            String bookId = "00000000-0000-0000-0000-000000000001";
            jdbc.update("INSERT INTO book_genres(book_id,genre_code) VALUES (?,?)", bookId, "fiction");

            CollectionManager manager = mock(CollectionManager.class);
            when(manager.getCurrentJdbcTemplate()).thenReturn(jdbc);
            BookGenreHelper helper = new BookGenreHelper(manager, mock(GenreRepository.class), new GenreRowMapper());

            var genres = helper.loadGenres(BookId.fromString(bookId));
            assertThat(genres).hasSize(1);
            assertThat(genres.getFirst().getId().asString()).isEqualTo("fiction");
            assertThat(genres.getFirst().getName()).isEqualTo("Фантастика");
            assertThat(genres.getFirst().getParentId().asString()).isEqualTo("literature");
            assertThat(genres.getFirst().getParentName()).isEqualTo("Література");
            assertThat(genres.getFirst().getFb2Code()).isEqualTo("sf");
        }
    }
}
