package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.port.out.SearchQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.ScoreDoc;
import org.apache.lucene.store.Directory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class LuceneSearchQueryService implements SearchQueryService {

    private final Directory directory;
    private final QueryParser queryParser;

    @Override
    public List<String> searchBookIds(String queryText, int limit) {
        List<String> ids = new ArrayList<>();
        if (queryText == null || queryText.isBlank()) {
            return ids;
        }

        try (IndexReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            String escapedQuery = QueryParser.escape(queryText.trim().toLowerCase());
            Query query = queryParser.parse(escapedQuery);
            ScoreDoc[] hits = searcher.search(query, limit).scoreDocs;
            for (ScoreDoc hit : hits) {
                org.apache.lucene.document.Document doc = searcher.doc(hit.doc);
                String id = doc.get("id");
                if (id != null && !id.isEmpty()) {
                    ids.add(id);
                }
            }
            log.debug("Знайдено {} ID для запиту '{}'", ids.size(), queryText);
        } catch (Exception e) {
            log.error("Помилка пошуку: {}", queryText, e);
        }
        return ids;
    }
}