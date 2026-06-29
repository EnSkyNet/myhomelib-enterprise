package com.myhomelibcorp.infrastructure.persistence.mapper;

import com.myhomelibcorp.domain.model.genre.Genre;
import com.myhomelibcorp.domain.model.valueobject.GenreId;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class GenreRowMapper implements RowMapper<Genre> {

    @Override
    public Genre mapRow(ResultSet rs, int rowNum) throws SQLException {
        GenreId id = GenreId.fromCode(rs.getString("code"));
        GenreId parentId = rs.getString("parent_code") != null
                ? GenreId.fromCode(rs.getString("parent_code"))
                : null;
        return new Genre(
                id,
                rs.getString("name"),
                parentId,
                rs.getString("fb2_code")
        );
    }
}