package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.application.query.search.SearchResult;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Stateless Lucene query execution; keeps LuceneSearchService focused on lifecycle/index orchestration. */
final class LuceneSearchExecutor {
    /** Keep deep-paging allocations bounded even for million-book catalogs. */
    private static final int SKIP_BATCH_SIZE = 10_000;
    /** Preserve the old public safety ceiling for one returned page, without capping the offset. */
    private static final int MAX_PAGE_SIZE = 100_000;

    private LuceneSearchExecutor() { }

    static SearchResult search(SearchRequest request, SearcherManager manager,
                               LuceneQueryNormalizer normalizer, LuceneUnifiedFilterBuilder filterBuilder) throws Exception {
        long started = System.currentTimeMillis();
        manager.maybeRefresh();
        IndexSearcher searcher = manager.acquire();
        try {
            BooleanQuery.Builder b = new BooleanQuery.Builder();
            String text = request.text() == null ? "" : request.text().trim();
            if (!text.isBlank()) b.add(normalizer.parse(text, request.mode()), BooleanClause.Occur.MUST);
            if (request.authorId() != null) b.add(term("author_id", request.authorId().asString()), BooleanClause.Occur.FILTER);
            if (request.genreId() != null) b.add(term("genre_id", request.genreId().asString()), BooleanClause.Occur.FILTER);
            if (request.language() != null) b.add(term("language", request.language().value().toLowerCase(Locale.ROOT)), BooleanClause.Occur.FILTER);
            if (request.ratingFrom() != null || request.ratingTo() != null) {
                int lo = request.ratingFrom() == null ? Integer.MIN_VALUE : request.ratingFrom();
                int hi = request.ratingTo() == null ? Integer.MAX_VALUE : request.ratingTo();
                b.add(IntPoint.newRangeQuery("library_rate_num", lo, hi), BooleanClause.Occur.FILTER);
            }
            if (request.yearFrom() != null || request.yearTo() != null) {
                int lo = request.yearFrom() == null ? Integer.MIN_VALUE : request.yearFrom();
                int hi = request.yearTo() == null ? Integer.MAX_VALUE : request.yearTo();
                b.add(IntPoint.newRangeQuery("year_num", lo, hi), BooleanClause.Occur.FILTER);
            }
            if (request.addedFrom() != null || request.addedTo() != null) {
                long lo = request.addedFrom() == null ? Long.MIN_VALUE : request.addedFrom().toEpochDay();
                long hi = request.addedTo() == null ? Long.MAX_VALUE : request.addedTo().toEpochDay();
                b.add(LongPoint.newRangeQuery("created_day", lo, hi), BooleanClause.Occur.FILTER);
            }
            if (request.localOnly() != null) b.add(term("local", request.localOnly() ? "1" : "0"), BooleanClause.Occur.FILTER);
            filterBuilder.addTo(b, request.filterSpec());
            b.add(term("deleted", "0"), BooleanClause.Occur.FILTER);
            Query query = b.build().clauses().isEmpty() ? new MatchAllDocsQuery() : b.build();

            int offset = Math.max(0, request.offset());
            int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, request.limit()));

            // IndexSearcher.search(Query,n) only tracks total hits accurately up to a threshold
            // (1000 in Lucene 9.x). SearchResult promises a total, so count explicitly.
            int totalHits = searcher.count(query);
            if (offset >= totalHits) {
                return new SearchResult(List.of(), totalHits, offset / limit, limit,
                        System.currentTimeMillis() - started);
            }

            ScoreDoc after = skipToOffset(searcher, query, offset);
            int pageSize = Math.min(limit, totalHits - offset);
            TopDocs page = searcher.searchAfter(after, query, pageSize);
            List<BookId> ids = new ArrayList<>(page.scoreDocs.length);
            for (ScoreDoc hit : page.scoreDocs) {
                Document doc = searcher.doc(hit.doc);
                String id = doc.get("id");
                if (id != null && !id.isEmpty()) ids.add(BookId.fromString(id));
            }
            return new SearchResult(ids, totalHits, offset / limit, limit,
                    System.currentTimeMillis() - started);
        } finally {
            manager.release(searcher);
        }
    }

    /**
     * Advances in bounded chunks instead of allocating TopDocs for {@code offset + limit}.
     * ScoreDoc contains Lucene's score/doc-id tie-break state, so it is safe to feed the
     * last hit into searchAfter for the next chunk.
     */
    private static ScoreDoc skipToOffset(IndexSearcher searcher, Query query, int offset) throws Exception {
        ScoreDoc after = null;
        int remaining = offset;
        while (remaining > 0) {
            int chunk = Math.min(SKIP_BATCH_SIZE, remaining);
            TopDocs skipped = searcher.searchAfter(after, query, chunk);
            if (skipped.scoreDocs.length == 0) return after;
            after = skipped.scoreDocs[skipped.scoreDocs.length - 1];
            remaining -= skipped.scoreDocs.length;
            if (skipped.scoreDocs.length < chunk) break;
        }
        return after;
    }

    private static TermQuery term(String field, String value) { return new TermQuery(new Term(field, value)); }
}
