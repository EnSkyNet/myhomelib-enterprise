package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.port.out.search.SearchQueryService;
import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.application.query.search.SearchResult;
import com.myhomelibcorp.domain.model.valueobject.BookId;
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

    // ---- СТАРИЙ МЕТОД (зворотна сумісність) ----
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

    // ---- НОВИЙ МЕТОД ----
    @Override
    public SearchResult search(SearchRequest request) {
        if (request == null || request.text() == null || request.text().isBlank()) {
            return SearchResult.empty();
        }

        long start = System.currentTimeMillis();
        List<BookId> bookIds = new ArrayList<>();

        try (IndexReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            String text = request.text().trim();
            String escapedText = QueryParser.escape(text);
            Query query;

            // В залежності від режиму будуємо різні запити
            switch (request.mode()) {
                case EXACT:
                    query = queryParser.parse("\"" + escapedText + "\"");
                    break;
                case PREFIX:
                    query = queryParser.parse(escapedText + "*");
                    break;
                case FUZZY:
                    query = queryParser.parse(escapedText + "~");
                    break;
                case PHRASE:
                default:
                    query = queryParser.parse(escapedText);
                    break;
            }

            ScoreDoc[] hits = searcher.search(query, request.limit()).scoreDocs;
            for (ScoreDoc hit : hits) {
                org.apache.lucene.document.Document doc = searcher.doc(hit.doc);
                String id = doc.get("id");
                if (id != null && !id.isEmpty()) {
                    bookIds.add(BookId.fromString(id));
                }
            }

            long elapsed = System.currentTimeMillis() - start;
            log.debug("Пошук '{}' знайшов {} результатів за {} мс", text, bookIds.size(), elapsed);

            return new SearchResult(
                    bookIds,
                    bookIds.size(),
                    request.offset() / request.limit(),
                    request.limit(),
                    elapsed
            );

        } catch (Exception e) {
            log.error("Помилка пошуку: {}", request.text(), e);
            return SearchResult.empty();
        }
    }
}