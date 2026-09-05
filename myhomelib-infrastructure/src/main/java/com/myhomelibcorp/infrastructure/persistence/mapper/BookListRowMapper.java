package com.myhomelibcorp.infrastructure.persistence.mapper;

import com.myhomelibcorp.domain.model.book.Book;
import com.myhomelibcorp.domain.model.valueobject.BookFile;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.domain.model.valueobject.BookMetadata;
import com.myhomelibcorp.domain.service.LanguageResolver;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.SqliteDateTimeCodec;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;

/** Maps the bounded, lightweight projection used by book tables/navigation lists. */
@Component
public class BookListRowMapper implements RowMapper<Book> {
    @Override
    public Book mapRow(ResultSet rs, int rowNum) throws SQLException {
        String collectionRoot = rs.getString("collection_root");
        BookFile file = new BookFile(
                value(rs.getString("file_name")),
                value(rs.getString("folder")),
                value(rs.getString("archive_entry")),
                rs.getLong("file_size"),
                value(collectionRoot));

        Integer year = rs.getInt("year");
        if (rs.wasNull()) year = null;
        BookMetadata metadata = BookMetadata.builder()
                .language(LanguageResolver.resolve(rs.getString("language")))
                .year(year)
                .rate(rs.getInt("rate"))
                .progress(rs.getInt("progress"))
                .build();

        LocalDateTime updateDate = SqliteDateTimeCodec.parse(rs.getString("update_date"));
        LocalDateTime createdAt = SqliteDateTimeCodec.parse(rs.getString("created_at"));
        return Book.builder()
                .id(BookId.fromString(rs.getString("id")))
                .title(value(rs.getString("title")))
                .series(value(rs.getString("series")))
                .sequenceNumber(rs.getInt("sequence_number"))
                .metadata(metadata)
                .file(file)
                .updateDate(updateDate != null ? updateDate : LocalDateTime.now())
                .createdAt(createdAt != null ? createdAt : LocalDateTime.now())
                .deleted(rs.getInt("deleted") == 1)
                .local(rs.getInt("local") == 1)
                .missingSince(SqliteDateTimeCodec.parse(rs.getString("missing_since")))
                .build();
    }

    private static String value(String value) {
        return value == null ? "" : value;
    }

}
