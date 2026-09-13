package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.query.search.SearchRequest;
import com.myhomelibcorp.application.query.search.SearchResult;
import com.myhomelibcorp.domain.model.search.SmartCollectionSortDirection;
import com.myhomelibcorp.domain.model.search.SmartCollectionSpec;
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
                               LuceneQueryNormalizer normalizer, LuceneUnifiedFilterBuilder filterBuilder,
                               LuceneSmartCollectionBuilder smartCollectionBuilder, LuceneCustomFieldFilterBuilder customFieldFilterBuilder) throws Exception {
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
            smartCollectionBuilder.addTo(b, request.smartCollectionSpec());
            customFieldFilterBuilder.addTo(b, request.customFieldFilters());
            b.add(term("deleted", "0"), BooleanClause.Occur.FILTER);
            Query query = b.build().clauses().isEmpty() ? new MatchAllDocsQuery() : b.build();

            int offset = Math.max(0, request.offset());
            int limit = Math.min(MAX_PAGE_SIZE, Math.max(1, request.limit()));
            int smartLimit = request.smartCollectionSpec() == null
                    ? Integer.MAX_VALUE : request.smartCollectionSpec().maxResults();
            Sort sort = smartSort(request.smartCollectionSpec());

            int countedHits = request.trackTotalHits() ? searcher.count(query) : -1;
            int totalHits = countedHits < 0 ? -1 : Math.min(countedHits, smartLimit);
            if ((totalHits >= 0 && offset >= totalHits) || offset >= smartLimit) {
                return new SearchResult(List.of(), totalHits, offset / limit, limit,
                        System.currentTimeMillis() - started);
            }

            ScoreDoc after = skipToOffset(searcher, query, offset, sort);
            int pageCeiling = Math.max(0, smartLimit - offset);
            int pageSize = totalHits >= 0 ? Math.min(limit, totalHits - offset) : Math.min(limit, pageCeiling);
            TopDocs page = searchAfter(searcher, after, query, pageSize, sort);
            List<BookId> ids = new ArrayList<>(page.scoreDocs.length);
            for (ScoreDoc hit : page.scoreDocs) {
                Document doc = searcher.storedFields().document(hit.doc);
                String id = doc.get("id");
                if (id != null && !id.isEmpty()) ids.add(BookId.fromString(id));
            }
            return new SearchResult(ids, totalHits, offset / limit, limit,
                    System.currentTimeMillis() - started);
        } finally {
            manager.release(searcher);
        }
    }

    private static TopDocs searchAfter(IndexSearcher searcher, ScoreDoc after, Query query, int limit, Sort sort) throws Exception {
        if (limit <= 0) return new TopDocs(new TotalHits(0, TotalHits.Relation.EQUAL_TO), new ScoreDoc[0]);
        return sort == null ? searcher.searchAfter(after, query, limit) : searcher.searchAfter(after, query, limit, sort);
    }

    private static ScoreDoc skipToOffset(IndexSearcher searcher, Query query, int offset, Sort sort) throws Exception {
        ScoreDoc after = null;
        int remaining = offset;
        while (remaining > 0) {
            int chunk = Math.min(SKIP_BATCH_SIZE, remaining);
            TopDocs skipped = searchAfter(searcher, after, query, chunk, sort);
            if (skipped.scoreDocs.length == 0) return after;
            after = skipped.scoreDocs[skipped.scoreDocs.length - 1];
            remaining -= skipped.scoreDocs.length;
            if (skipped.scoreDocs.length < chunk) break;
        }
        return after;
    }

    private static Sort smartSort(SmartCollectionSpec spec) {
        if (spec == null) return null;
        boolean reverse = spec.direction() == SmartCollectionSortDirection.DESC;
        SortField primary = switch (spec.sort()) {
            case TITLE -> new SortField("title_sort", SortField.Type.STRING, reverse);
            case AUTHOR -> new SortField("author_sort", SortField.Type.STRING, reverse);
            case SERIES -> new SortField("series_sort", SortField.Type.STRING, reverse);
            case YEAR -> new SortField("year_sort", SortField.Type.INT, reverse);
            case RATING -> new SortField("rate_sort", SortField.Type.INT, reverse);
            case PROGRESS -> new SortField("progress_sort", SortField.Type.INT, reverse);
            case ADDED -> new SortField("created_sort", SortField.Type.LONG, reverse);
        };
        SortField id = new SortField("id_sort", SortField.Type.STRING, reverse);
        return new Sort(primary, id);
    }

    private static TermQuery term(String field, String value) { return new TermQuery(new Term(field, value)); }
}
