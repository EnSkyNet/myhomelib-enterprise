package com.myhomelibcorp.infrastructure.persistence.mapper;

import com.myhomelibcorp.domain.model.series.Series;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
public class SeriesRowMapper implements RowMapper<Series> {

    @Override
    public Series mapRow(ResultSet rs, int rowNum) throws SQLException {
        String id = rs.getString("id");
        String name = rs.getString("name");
        return new Series(id, name, null);
    }
}