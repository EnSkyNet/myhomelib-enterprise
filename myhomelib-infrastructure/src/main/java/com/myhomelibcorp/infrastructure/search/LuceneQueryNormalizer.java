package com.myhomelibcorp.infrastructure.search;

import com.myhomelibcorp.application.query.search.SearchMode;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.Query;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Classic MyHomeLib syntax compatibility kept separate from search execution. */
final class LuceneQueryNormalizer {
    private final QueryParser queryParser;

    LuceneQueryNormalizer(QueryParser queryParser) {
        this.queryParser = queryParser;
    }

    Query parse(String raw, SearchMode mode) throws Exception {
        String text = normalizeClassicSearchSyntax(raw);
        boolean hasSyntax = text.contains(":") || text.contains(" OR ") || text.contains("*") || text.contains("?")
                || text.startsWith("\"") || text.contains(" AND ") || text.contains(" NOT ");
        if (!hasSyntax) {
            String escaped = QueryParser.escape(text);
            return switch (mode) {
                case EXACT -> queryParser.parse("\"" + escaped + "\"");
                case PREFIX -> queryParser.parse(escaped + "*");
                case FUZZY -> queryParser.parse(escaped + "~");
                default -> queryParser.parse(escaped);
            };
        }
        try {
            return queryParser.parse(text);
        } catch (Exception syntaxError) {
            return queryParser.parse(QueryParser.escape(raw));
        }
    }

    String normalizeClassicSearchSyntax(String raw) {
        String s = raw == null ? "" : raw.trim();
        if (s.isEmpty()) return s;
        s = s.replaceAll("(?i)\\bauthor:", "authors:")
                .replaceAll("(?iU)\\bавтор:", "authors:")
                .replaceAll("(?i)\\bgenre:", "genres:")
                .replaceAll("(?iU)\\bжанр:", "genres:")
                .replaceAll("(?i)\\bfile(name)?:", "file_name:")
                .replaceAll("(?iU)\\bфайл:", "file_name:")
                .replaceAll("(?i)\\blang(uage)?:", "language:")
                .replaceAll("(?iU)\\bмова:", "language:")
                .replaceAll("(?iU)\\bназва:", "title:")
                .replaceAll("(?iU)\\bсерія:", "series:")
                .replaceAll("(?iU)\\bанотація:", "annotation:")
                .replaceAll("(?iU)\\bключові(?:слова)?:", "keywords:")
                .replaceAll("(?i)\\bpub(lisher)?:", "publisher:")
                .replaceAll("(?iU)\\bвидавець:", "publisher:")
                .replaceAll("(?iU)\\bвидавництво:", "publisher:")
                .replaceAll("(?i)\\blib(rate|raryrate):", "library_rate:")
                .replaceAll("(?iU)\\bрейтингбібліотеки:", "library_rate:")
                .replaceAll("(?i)\\buser(rate|rating):", "rate:")
                .replaceAll("(?iU)\\bмійрейтинг:", "rate:")
                .replaceAll("(?i)\\blibid:", "lib_id:")
                .replaceAll("(?iU)\\bперекладачі?:", "translators:")
                .replaceAll("(?iU)\\bмісто:", "city:")
                .replaceAll("(?i)\\badded:", "created:")
                .replaceAll("(?i)\\bdateadded:", "created:")
                .replaceAll("(?iU)\\bдодано:", "created:");

        s = s.replaceAll("(?i)\\b(?:added|dateadded)\\s*(<>|>=|<=|>|<|=)", "created$1")
                .replaceAll("(?iU)\\bдодано\\s*(<>|>=|<=|>|<|=)", "created$1");
        s = s.replaceAll("(?i)\\b(year|created|library_rate|rate)\\s*(<>|>=|<=|>|<|=)\\s*([^\\s)]+)", "$1:$2$3");
        s = normalizeComparison(s, "<>");
        s = normalizeComparison(s, ">=");
        s = normalizeComparison(s, "<=");
        s = normalizeComparison(s, ">");
        s = normalizeComparison(s, "<");
        s = s.replaceAll("(?i)(\\b[a-z_]+:)\\s*=\\s*\\\"([^\\\"]+)\\\"", "$1\\\"$2\\\"");
        if (s.startsWith("=\"") && s.endsWith("\"") && s.length() > 3) s = s.substring(1);
        return normalizePercentWildcards(s);
    }

    private String normalizeComparison(String input, String operator) {
        String op = Pattern.quote(operator);
        Pattern pattern = Pattern.compile("(?i)\\b(year|created|library_rate|rate):\\s*" + op + "\\s*([^\\s)]+)");
        Matcher m = pattern.matcher(input);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String field = m.group(1);
            String value = normalizeComparableValue(field, m.group(2));
            String replacement = switch (operator) {
                case "<>" -> "(*:* AND NOT " + field + ":" + value + ")";
                case ">=" -> field + ":[" + value + " TO *]";
                case ">" -> field + ":{" + value + " TO *]";
                case "<=" -> field + ":[* TO " + value + "]";
                case "<" -> field + ":[* TO " + value + "}";
                default -> field + ":" + value;
            };
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }

    private String normalizeComparableValue(String field, String value) {
        String v = value == null ? "" : value.trim().replace("\\\"", "");
        if ("created".equalsIgnoreCase(field)) return v.replaceAll("[^0-9]", "");
        if ("year".equalsIgnoreCase(field)) {
            try { return String.format(Locale.ROOT, "%04d", Integer.parseInt(v)); }
            catch (NumberFormatException ignored) { return QueryParser.escape(v); }
        }
        return QueryParser.escape(v);
    }

    private String normalizePercentWildcards(String input) {
        Matcher m = Pattern.compile("%([^%]+)%").matcher(input);
        StringBuffer out = new StringBuffer();
        while (m.find()) {
            String replacement = "*" + QueryParser.escape(m.group(1)) + "*";
            m.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(out);
        return out.toString();
    }
}
