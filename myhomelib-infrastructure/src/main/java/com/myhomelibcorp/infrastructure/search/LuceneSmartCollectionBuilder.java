package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.domain.model.search.SmartCollectionMode;
import com.myhomelibcorp.domain.model.search.SmartCollectionOperator;
import com.myhomelibcorp.domain.model.search.SmartCollectionRule;
import com.myhomelibcorp.domain.model.search.SmartCollectionSpec;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.WildcardQuery;

import java.util.Locale;

/** Compiles the typed MHL-114 smart-collection definition to Lucene filters. */
final class LuceneSmartCollectionBuilder {
    void addTo(BooleanQuery.Builder outer, SmartCollectionSpec spec) {
        if (spec == null || spec.rules().isEmpty()) return;
        BooleanQuery.Builder group = new BooleanQuery.Builder();
        BooleanClause.Occur occur = spec.mode() == SmartCollectionMode.OR
                ? BooleanClause.Occur.SHOULD : BooleanClause.Occur.MUST;
        for (SmartCollectionRule rule : spec.rules()) {
            group.add(ruleQuery(rule), occur);
        }
        if (spec.mode() == SmartCollectionMode.OR) group.setMinimumNumberShouldMatch(1);
        outer.add(group.build(), BooleanClause.Occur.FILTER);
    }

    private Query ruleQuery(SmartCollectionRule rule) {
        return switch (rule.field()) {
            case TITLE -> textRule("title", "title_exact", rule);
            case AUTHOR -> textRule("authors", "author_name_exact", rule);
            case SERIES -> textRule("series", "series_exact", rule);
            case GENRE -> textRule("genres", "genre_name_exact", rule);
            case KEYWORD -> textRule("keywords", "keyword_exact", rule);
            case PUBLISHER -> textRule("publisher", "publisher_exact", rule);
            case LANGUAGE -> enumRule("language", rule);
            case FORMAT -> enumRule("format", rule);
            case YEAR -> numericRule("year_num", rule);
            case PROGRESS -> numericRule("progress_num", rule);
            case RATING -> numericRule("rate_num", rule);
            case LOCAL -> new TermQuery(new Term("local",
                    rule.operator() == SmartCollectionOperator.IS_TRUE ? "1" : "0"));
        };
    }

    private Query textRule(String analyzedField, String exactField, SmartCollectionRule rule) {
        return switch (rule.operator()) {
            case CONTAINS -> contains(analyzedField, rule.value());
            case EQUALS -> new TermQuery(new Term(exactField, normalize(rule.value())));
            case NOT_EQUALS -> negated(new TermQuery(new Term(exactField, normalize(rule.value()))));
            default -> throw unsupported(rule);
        };
    }

    private Query enumRule(String field, SmartCollectionRule rule) {
        Query exact = new TermQuery(new Term(field, normalize(rule.value())));
        return switch (rule.operator()) {
            case EQUALS -> exact;
            case NOT_EQUALS -> negated(exact);
            default -> throw unsupported(rule);
        };
    }

    private Query numericRule(String field, SmartCollectionRule rule) {
        int first = Integer.parseInt(rule.value());
        Query exact = IntPoint.newExactQuery(field, first);
        return switch (rule.operator()) {
            case EQUALS -> exact;
            case NOT_EQUALS -> negated(exact);
            case AT_LEAST -> IntPoint.newRangeQuery(field, first, Integer.MAX_VALUE);
            case AT_MOST -> IntPoint.newRangeQuery(field, Integer.MIN_VALUE, first);
            case BETWEEN -> IntPoint.newRangeQuery(field, first, Integer.parseInt(rule.secondValue()));
            default -> throw unsupported(rule);
        };
    }

    private Query contains(String field, String raw) {
        String normalized = normalize(raw);
        BooleanQuery.Builder allTokens = new BooleanQuery.Builder();
        for (String token : normalized.split("\\s+")) {
            if (token.isBlank()) continue;
            allTokens.add(new WildcardQuery(new Term(field, "*" + escapeWildcard(token) + "*")),
                    BooleanClause.Occur.MUST);
        }
        BooleanQuery built = allTokens.build();
        return built.clauses().isEmpty() ? new MatchAllDocsQuery() : built;
    }

    private Query negated(Query query) {
        BooleanQuery.Builder b = new BooleanQuery.Builder();
        b.add(new MatchAllDocsQuery(), BooleanClause.Occur.MUST);
        b.add(query, BooleanClause.Occur.MUST_NOT);
        return b.build();
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String escapeWildcard(String value) {
        return value.replace("\\", "\\\\")
                .replace("*", "\\*")
                .replace("?", "\\?");
    }

    private IllegalArgumentException unsupported(SmartCollectionRule rule) {
        return new IllegalArgumentException("Unsupported smart collection rule: "
                + rule.field() + " " + rule.operator());
    }
}
