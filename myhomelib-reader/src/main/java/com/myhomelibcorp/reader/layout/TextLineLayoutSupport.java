package com.myhomelibcorp.reader.layout;

import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.api.StyleSpan;
import com.myhomelibcorp.reader.api.TextStyle;
import com.myhomelibcorp.reader.model.TextRunLayout;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Stateless-at-call-site line breaking and inline-run composition used by
 * {@link TextLayoutEngine}. Keeping this logic separate makes the page
 * orchestrator easier to reason about while preserving source-offset semantics.
 */
final class TextLineLayoutSupport {
    private final HyphenationService hyphenationService = new HyphenationService();
    private FontMetricsProvider fontMetrics;
    private ReaderSettings settings;

    TextLineLayoutSupport(FontMetricsProvider fontMetrics, ReaderSettings settings) {
        this.fontMetrics = fontMetrics;
        this.settings = settings;
    }

    void update(FontMetricsProvider fontMetrics, ReaderSettings settings) {
        this.fontMetrics = fontMetrics;
        this.settings = settings;
    }

    LineBreak findLineEnd(
            String text,
            int start,
            float maxWidth,
            TextStyle paragraphStyle,
            float baseFontSize,
            List<StyleSpan> spans,
            String language
    ) {
        float width = 0f;
        int lastBreak = -1;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '\n' || c == '\r') {
                return new LineBreak(i + 1, false);
            }

            TextStyle style = styleAt(spans, i, paragraphStyle);
            float runFontSize = inlineFontSize(style, baseFontSize);
            float charWidth = fontMetrics.getCharWidth(c, style, runFontSize);
            if (width + charWidth > maxWidth) {
                if (i == start) return new LineBreak(i + 1, false);
                if (lastBreak > start) return new LineBreak(lastBreak, false);
                if (settings.hyphenation()) {
                    int wordEnd = i;
                    while (wordEnd < text.length() && Character.isLetter(text.charAt(wordEnd))) wordEnd++;
                    String word = text.substring(start, wordEnd);
                    List<Integer> candidates = hyphenationService.candidates(word, language);
                    for (int n = candidates.size() - 1; n >= 0; n--) {
                        int candidateEnd = start + candidates.get(n);
                        if (candidateEnd <= start + 1 || candidateEnd > i) continue;
                        float candidateWidth = measureRange(text, start, candidateEnd, paragraphStyle, baseFontSize, spans);
                        TextStyle hyphenStyle = styleAt(spans, Math.max(start, candidateEnd - 1), paragraphStyle);
                        candidateWidth += fontMetrics.getCharWidth('‐', hyphenStyle, inlineFontSize(hyphenStyle, baseFontSize));
                        if (candidateWidth <= maxWidth) return new LineBreak(candidateEnd, true);
                    }
                }
                return new LineBreak(i, false);
            }

            width += charWidth;
            if (Character.isWhitespace(c) || c == '-' || c == '\u2010' || c == '\u2013') {
                lastBreak = i + 1;
            }
        }
        return new LineBreak(text.length(), false);
    }

    float measureRange(
            String text,
            int start,
            int end,
            TextStyle paragraphStyle,
            float baseFontSize,
            List<StyleSpan> spans
    ) {
        float width = 0f;
        for (int i = start; i < end; i++) {
            TextStyle style = styleAt(spans, i, paragraphStyle);
            width += fontMetrics.getCharWidth(text.charAt(i), style, inlineFontSize(style, baseFontSize));
        }
        return width;
    }

    /** Build inline runs only when styling/justification requires them. */
    List<TextRunLayout> buildVisualRuns(
            String text,
            int start,
            int end,
            int absoluteStartOffset,
            TextStyle paragraphStyle,
            float baseFontSize,
            List<StyleSpan> spans,
            float extraPerSpace,
            boolean hyphenated
    ) {
        boolean styleRunsNeeded = spans != null && !spans.isEmpty()
                && hasDifferentStyle(spans, start, end, paragraphStyle);
        boolean justifyRunsNeeded = extraPerSpace > 0.01f;
        if (start >= end || (!styleRunsNeeded && !justifyRunsNeeded)) {
            return List.of();
        }

        List<TextRunLayout> runs = new ArrayList<>();
        int runStart = start;
        TextStyle runStyle = styleAt(spans, start, paragraphStyle);
        float x = 0f;

        for (int i = start; i < end; i++) {
            TextStyle style = styleAt(spans, i, paragraphStyle);
            if (style != runStyle && i > runStart) {
                x = addRun(runs, text, runStart, i, absoluteStartOffset, runStyle, baseFontSize, x);
                runStart = i;
                runStyle = style;
            } else if (style != runStyle) {
                runStyle = style;
            }

            if (justifyRunsNeeded && text.charAt(i) == ' ') {
                x = addRun(runs, text, runStart, i + 1, absoluteStartOffset, runStyle, baseFontSize, x);
                x += extraPerSpace;
                runStart = i + 1;
                if (runStart < end) {
                    runStyle = styleAt(spans, runStart, paragraphStyle);
                }
            }
        }

        if (runStart < end) {
            x = addRun(runs, text, runStart, end, absoluteStartOffset, runStyle, baseFontSize, x);
        }
        if (hyphenated && (styleRunsNeeded || justifyRunsNeeded)) {
            TextStyle hyphenStyle = end > start ? styleAt(spans, end - 1, paragraphStyle) : paragraphStyle;
            float hyphenSize = inlineFontSize(hyphenStyle, baseFontSize);
            float hyphenWidth = fontMetrics.getCharWidth('‐', hyphenStyle, hyphenSize);
            runs.add(new TextRunLayout("‐", x, hyphenWidth, hyphenSize, hyphenStyle,
                    absoluteStartOffset + end, 0));
        }
        return List.copyOf(runs);
    }

    boolean shouldJustifyLine(
            String text,
            int start,
            int displayEnd,
            int rawEnd,
            float maxWidth,
            float naturalWidth,
            TextStyle paragraphStyle
    ) {
        String alignment = settings.alignment() == null
                ? "left"
                : settings.alignment().toLowerCase(Locale.ROOT);
        if (!"justify".equals(alignment) || paragraphStyle.isHeading()) return false;
        if (displayEnd <= start || rawEnd >= text.length()) return false;
        char breakChar = rawEnd > 0 ? text.charAt(rawEnd - 1) : 0;
        if (breakChar == '\n' || breakChar == '\r') return false;
        if (naturalWidth < maxWidth * 0.65f) return false;
        return countSpaces(text, start, displayEnd) > 0;
    }

    int countSpaces(String text, int start, int end) {
        int count = 0;
        for (int i = start; i < end; i++) {
            if (text.charAt(i) == ' ') count++;
        }
        return count;
    }

    TextStyle styleAt(List<StyleSpan> spans, int offset, TextStyle fallback) {
        if (spans == null || spans.isEmpty()) return fallback;

        int lo = 0;
        int hi = spans.size() - 1;
        int candidate = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            StyleSpan span = spans.get(mid);
            if (span.start() <= offset) {
                candidate = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }

        if (candidate >= 0) {
            StyleSpan span = spans.get(candidate);
            if (offset < span.end() && span.style() != null) return span.style();
        }
        return fallback;
    }

    float inlineFontSize(TextStyle style, float baseFontSize) {
        if (style == TextStyle.SUPERSCRIPT || style == TextStyle.SUBSCRIPT) {
            return baseFontSize * 0.76f;
        }
        return baseFontSize;
    }

    int skipLeadingLineWhitespace(String text, int cursor) {
        int i = cursor;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c == ' ') i++;
            else break;
        }
        return i;
    }

    boolean isSoftWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    float resolveLineX(float baseX, int contentWidth, float lineIndent, float lineWidth) {
        String alignment = settings.alignment() == null
                ? "left"
                : settings.alignment().toLowerCase(Locale.ROOT);
        return switch (alignment) {
            case "center" -> baseX + Math.max(0f, (contentWidth - lineWidth) / 2f);
            case "right" -> baseX + Math.max(0f, contentWidth - lineWidth);
            default -> baseX + lineIndent;
        };
    }

    float resolveFontSize(TextStyle style) {
        float base = (float) settings.fontSize();
        if (style == null) return base;
        return switch (style) {
            case HEADING_1 -> base * 1.55f;
            case HEADING_2 -> base * 1.35f;
            case HEADING_3 -> base * 1.20f;
            case HEADING_4, HEADING_5, HEADING_6 -> base * 1.10f;
            case NOTE, FOOTNOTE -> base * 0.90f;
            default -> base;
        };
    }

    float paragraphGap(TextStyle style) {
        double multiplier = style != null && style.isHeading() ? 0.65 : 0.25;
        return (float) Math.max(0, settings.paragraphSpacing() * settings.fontSize() * multiplier);
    }

    private boolean hasDifferentStyle(List<StyleSpan> spans, int start, int end, TextStyle paragraphStyle) {
        for (StyleSpan span : spans) {
            if (span.end() <= start) continue;
            if (span.start() >= end) break;
            if (span.style() != null && span.style() != paragraphStyle) return true;
        }
        return false;
    }

    private float addRun(
            List<TextRunLayout> runs,
            String text,
            int start,
            int end,
            int absoluteStartOffset,
            TextStyle style,
            float baseFontSize,
            float x
    ) {
        if (start >= end) return x;
        TextStyle effective = style != null ? style : TextStyle.NORMAL;
        String runText = text.substring(start, end);
        float runFontSize = inlineFontSize(effective, baseFontSize);
        float runWidth = fontMetrics.getStringWidth(runText, effective, runFontSize);
        runs.add(new TextRunLayout(
                runText, x, runWidth, runFontSize, effective,
                absoluteStartOffset + start, end - start
        ));
        return x + runWidth;
    }

    record LineBreak(int end, boolean hyphenated) {}
}
