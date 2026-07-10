package com.myhomelibcorp.infrastructure.persistence.sqlite;

import com.myhomelibcorp.application.port.out.persistence.PragmaConfigurator;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SqlitePragmaConfigurator implements PragmaConfigurator {

    private final CollectionManager collectionManager;

    private JdbcTemplate getJdbcTemplate() {
        return collectionManager.getCurrentJdbcTemplate();
    }

    @Override
    public void setPragmaForBulkInsert() {
        JdbcTemplate jt = getJdbcTemplate();
        jt.execute("PRAGMA synchronous = OFF");
        jt.execute("PRAGMA journal_mode = MEMORY");
        jt.execute("PRAGMA temp_store = MEMORY");
        jt.execute("PRAGMA cache_size = -500000"); // 500 MB
        log.debug("PRAGMA встановлено для швидкого імпорту");
    }

    @Override
    public void resetPragma() {
        JdbcTemplate jt = getJdbcTemplate();
        jt.execute("PRAGMA synchronous = NORMAL");
        jt.execute("PRAGMA journal_mode = DELETE");
        log.debug("PRAGMA відновлено до стандартних");
    }
}