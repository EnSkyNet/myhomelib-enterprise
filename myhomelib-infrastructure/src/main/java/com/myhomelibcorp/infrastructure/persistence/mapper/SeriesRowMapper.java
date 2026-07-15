package com.myhomelibcorp.infrastructure.persistence.mapper;

import com.myhomelibcorp.domain.model.series.Series;
import com.myhomelibcorp.domain.model.valueobject.SeriesId;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Component;

import java.sql.ResultSet;
import java.sql.SQLException;

@Component
@Slf4j
public class SeriesRowMapper implements RowMapper<Series> {

    @Override
    public Series mapRow(ResultSet rs, int rowNum) throws SQLException {
        String idStr = rs.getString("id");
        SeriesId id;
        try {
            id = SeriesId.fromString(idStr);
        } catch (IllegalArgumentException e) {
            // Якщо ID не є валідним UUID (наприклад, старі дані), генеруємо новий
            log.warn("Некоректний ID серії '{}', генеруємо новий UUID", idStr);
            id = SeriesId.generate();
        }
        String name = rs.getString("name");
        return new Series(id, name, null);
    }
}