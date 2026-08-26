package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.filter.BookFilterMode;
import com.myhomelibcorp.application.filter.BookFilterSpec;
import com.myhomelibcorp.application.filter.BookQuickFilterField;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.WildcardQuery;

import java.util.List;
import java.util.Locale;

/** Builds the Lucene equivalent of the application-level unified BookFilterSpec. */
final class LuceneUnifiedFilterBuilder {
    void addTo(BooleanQuery.Builder outer, BookFilterSpec filter) {
        if (filter == null || !filter.isActive()) return;
        BooleanQuery.Builder group = new BooleanQuery.Builder();
        BooleanClause.Occur occur = filter.mode() == BookFilterMode.OR
                ? BooleanClause.Occur.SHOULD : BooleanClause.Occur.MUST;
        int criteria = 0;

        if (filter.language() != null) {
            group.add(new TermQuery(new Term("language", filter.language().toLowerCase(Locale.ROOT))), occur); criteria++;
        }
        if (filter.yearFrom() != null || filter.yearTo() != null) {
            int lo = filter.yearFrom() == null ? Integer.MIN_VALUE : filter.yearFrom();
            int hi = filter.yearTo() == null ? Integer.MAX_VALUE : filter.yearTo();
            group.add(IntPoint.newRangeQuery("year_num", lo, hi), occur); criteria++;
        }
        if (filter.format() != null) {
            group.add(new TermQuery(new Term("format", filter.format().name().toLowerCase(Locale.ROOT))), occur); criteria++;
        }
        if (filter.local() != null) {
            group.add(new TermQuery(new Term("local", filter.local() ? "1" : "0")), occur); criteria++;
        }
        if (filter.read() != null) {
            group.add(new TermQuery(new Term("read", filter.read() ? "1" : "0")), occur); criteria++;
        }
        if (filter.ratingMin() != null || filter.ratingMax() != null) {
            int lo = filter.ratingMin() == null ? Integer.MIN_VALUE : filter.ratingMin();
            int hi = filter.ratingMax() == null ? Integer.MAX_VALUE : filter.ratingMax();
            group.add(IntPoint.newRangeQuery("rate_num", lo, hi), occur); criteria++;
        }
        if (filter.hideUnrated()) {
            group.add(IntPoint.newRangeQuery("rate_num", 1, Integer.MAX_VALUE), occur); criteria++;
        }
        if (filter.quickValue() != null) {
            group.add(quickFilterQuery(filter.quickField(), filter.quickValue()), occur); criteria++;
        }

        if (criteria == 0) return;
        if (filter.mode() == BookFilterMode.OR) group.setMinimumNumberShouldMatch(1);
        outer.add(group.build(), BooleanClause.Occur.FILTER);
    }

    private Query quickFilterQuery(BookQuickFilterField field, String value) {
        String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) return new MatchAllDocsQuery();

        List<String> fields = field == null || field == BookQuickFilterField.ANY
                ? List.of("title", "authors", "series", "genres", "keywords", "publisher", "file_name")
                : List.of(switch (field) {
                    case TITLE -> "title";
                    case AUTHOR -> "authors";
                    case SERIES -> "series";
                    case GENRE -> "genres";
                    case KEYWORD -> "keywords";
                    case PUBLISHER -> "publisher";
                    case FILE -> "file_name";
                    case ANY -> "title";
                });

        BooleanQuery.Builder allTokens = new BooleanQuery.Builder();
        for (String token : normalized.split("\\s+")) {
            if (token.isBlank()) continue;
            String wildcard = "*" + escapeWildcardTerm(token) + "*";
            BooleanQuery.Builder oneToken = new BooleanQuery.Builder();
            for (String luceneField : fields) {
                oneToken.add(new WildcardQuery(new Term(luceneField, wildcard)), BooleanClause.Occur.SHOULD);
            }
            oneToken.setMinimumNumberShouldMatch(1);
            allTokens.add(oneToken.build(), BooleanClause.Occur.MUST);
        }
        return allTokens.build();
    }

    private String escapeWildcardTerm(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (char c : value.toCharArray()) {
            if (c == '*' || c == '?' || c == '\\') out.append('\\');
            out.append(c);
        }
        return out.toString();
    }
}
