package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.query.search.CustomFieldSearchFilter;
import org.apache.lucene.document.DoublePoint;
import org.apache.lucene.document.LongPoint;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.*;

import java.time.LocalDate;
import java.util.List;
import java.util.Locale;

final class LuceneCustomFieldFilterBuilder {
    void addTo(BooleanQuery.Builder outer, List<CustomFieldSearchFilter> filters) {
        if (filters == null) return;
        for (CustomFieldSearchFilter filter : filters) {
            if (filter == null) continue;
            outer.add(toQuery(filter), BooleanClause.Occur.FILTER);
        }
    }

    private Query toQuery(CustomFieldSearchFilter f) {
        String id = Long.toString(f.definitionId());
        return switch (f.type()) {
            case TEXT -> textQuery(id, f);
            case ENUM -> exactQuery("cf_exact_" + id, f.value());
            case NUMBER -> numberQuery("cf_num_" + id, f);
            case BOOL -> new TermQuery(new Term("cf_bool_" + id,
                    f.operator().name().equals("IS_FALSE") ? "false" : "true"));
            case DATE -> dateQuery("cf_date_" + id, f);
        };
    }

    private Query textQuery(String id, CustomFieldSearchFilter f) {
        String value = normalize(f.value());
        if (f.operator().name().equals("EQUALS")) return exactQuery("cf_exact_" + id, value);
        return new WildcardQuery(new Term("cf_text_" + id, "*" + escape(value) + "*"));
    }

    private Query exactQuery(String field, String value) {
        return new TermQuery(new Term(field, normalize(value)));
    }

    private Query numberQuery(String field, CustomFieldSearchFilter f) {
        double first = Double.parseDouble(f.value());
        return switch (f.operator()) {
            case EQUALS -> DoublePoint.newExactQuery(field, first);
            case AT_LEAST -> DoublePoint.newRangeQuery(field, first, Double.POSITIVE_INFINITY);
            case AT_MOST -> DoublePoint.newRangeQuery(field, Double.NEGATIVE_INFINITY, first);
            case BETWEEN -> DoublePoint.newRangeQuery(field, first, Double.parseDouble(f.secondValue()));
            default -> throw new IllegalArgumentException("Unsupported numeric custom field operator: " + f.operator());
        };
    }

    private Query dateQuery(String field, CustomFieldSearchFilter f) {
        long first = LocalDate.parse(f.value()).toEpochDay();
        return switch (f.operator()) {
            case EQUALS -> LongPoint.newExactQuery(field, first);
            case AT_LEAST -> LongPoint.newRangeQuery(field, first, Long.MAX_VALUE);
            case AT_MOST -> LongPoint.newRangeQuery(field, Long.MIN_VALUE, first);
            case BETWEEN -> LongPoint.newRangeQuery(field, first, LocalDate.parse(f.secondValue()).toEpochDay());
            default -> throw new IllegalArgumentException("Unsupported date custom field operator: " + f.operator());
        };
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder();
        for (char c : value.toCharArray()) { if (c == '*' || c == '?' || c == '\\') out.append('\\'); out.append(c); }
        return out.toString();
    }
}
