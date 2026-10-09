package com.myhomelibcorp.ui.search;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/** Builds case-insensitive highlight runs while preserving offsets in the original UTF-16 text. */
final class SearchHighlightSupport {
    private SearchHighlightSupport() { }

    static List<Segment> segments(String value, String needle) {
        String text = value == null ? "" : value;
        if (text.isEmpty()) return List.of();
        if (needle == null || needle.isBlank()) return List.of(new Segment(text, false));

        Pattern literal = Pattern.compile(Pattern.quote(needle), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);
        var matcher = literal.matcher(text);
        List<Segment> result = new ArrayList<>();
        int from = 0;
        while (matcher.find()) {
            if (matcher.start() > from) result.add(new Segment(text.substring(from, matcher.start()), false));
            result.add(new Segment(text.substring(matcher.start(), matcher.end()), true));
            from = matcher.end();
        }
        if (from < text.length()) result.add(new Segment(text.substring(from), false));
        if (result.isEmpty()) result.add(new Segment(text, false));
        return List.copyOf(result);
    }

    record Segment(String text, boolean highlighted) { }
}
