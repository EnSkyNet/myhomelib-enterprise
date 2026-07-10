package com.myhomelibcorp.infrastructure.performance;

import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class QueryPlanAnalyzer {

    private final CollectionManager collectionManager;

    public void analyze(String sql) {
        JdbcTemplate jt = collectionManager.getCurrentJdbcTemplate();
        String explainSql = "EXPLAIN QUERY PLAN " + sql;
        List<String> plan = jt.query(explainSql, (rs, row) ->
                rs.getInt("id") + "|" + rs.getInt("parent") + "|" + rs.getInt("notused") +
                        "|" + rs.getString("detail"));
        log.info("📊 Query Plan for: {}", sql);
        for (String line : plan) {
            log.info("  {}", line);
        }
    }

    public void analyzeAndLogAllQueries() {
        // аналізує основні запити
        analyze("SELECT * FROM books WHERE title LIKE '%test%'");
        analyze("SELECT * FROM books b JOIN book_authors ba ON b.id = ba.book_id WHERE ba.author_id = 'some'");
    }
}