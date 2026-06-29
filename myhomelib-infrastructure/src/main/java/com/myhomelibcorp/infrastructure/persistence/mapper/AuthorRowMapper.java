package com.myhomelibcorp.infrastructure.persistence.mapper;

import com.myhomelibcorp.domain.model.author.Author;
import com.myhomelibcorp.domain.model.valueobject.AuthorId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class AuthorRowMapper implements RowMapper<Author> {

    @Override
    public Author mapRow(ResultSet rs, int rowNum) throws SQLException {
        AuthorId id = AuthorId.fromString(rs.getString("id"));
        return new Author(
                id,
                rs.getString("first_name"),
                rs.getString("middle_name"),
                rs.getString("last_name")
        );
    }
}