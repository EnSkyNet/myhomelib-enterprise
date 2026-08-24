package com.myhomelibcorp.reader.layout;

import com.myhomelibcorp.reader.api.PageDimensions;
import com.myhomelibcorp.reader.api.ParagraphInfo;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.api.ReaderSettings;
import com.myhomelibcorp.reader.api.StyleSpan;
import com.myhomelibcorp.reader.api.TextStorage;
import com.myhomelibcorp.reader.api.TextStyle;
import com.myhomelibcorp.reader.model.LineLayout;
import com.myhomelibcorp.reader.model.PageLayout;
import com.myhomelibcorp.reader.model.ParagraphLayout;
import com.myhomelibcorp.reader.model.TextRunLayout;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Посторінковий reflow layout. Працює тільки з поточною сторінкою, тому не
 * будує повну карту сторінок книги в пам'яті.
 *
 * Inline FB2-стилі зберігаються у компактних TextRunLayout тільки для тих
 * рядків, де вони реально є. Звичайний текст не отримує додаткових run-об'єктів.
 */
@Slf4j
public class TextLayoutEngine {

    private FontMetricsProvider fontMetrics;
    private ReaderSettings settings;

    public TextLayoutEngine(FontMetricsProvider fontMetrics, ReaderSettings settings) {
        this.fontMetrics = fontMetrics;
        this.settings = settings != null ? settings : ReaderSettings.defaultSettings();
    }

    public void updateSettings(ReaderSettings newSettings) {
        if (newSettings == null) {
            return;
        }
        this.settings = newSettings;
        this.fontMetrics = this.fontMetrics.withSettings(newSettings);
    }

    public PageLayout layoutPage(ReaderDocument document, long textOffset, PageDimensions dimensions) {
        if (document == null || dimensions == null || !dimensions.isValid()) {
            return PageLayout.empty();
        }

        TextStorage text = document.text();
        if (text == null || text.length() == 0 || text.getParagraphCount() == 0) {
            return PageLayout.empty();
        }

        long startTime = System.currentTimeMillis();
        int clampedOffset = (int) Math.max(0, Math.min(textOffset, Math.max(0, text.length() - 1L)));

        ParagraphInfo firstParagraph = text.findParagraphAt(clampedOffset);
        if (firstParagraph == null) {
            List<ParagraphInfo> paragraphs = text.getParagraphs();
            if (paragraphs.isEmpty()) {
                return PageLayout.empty();
            }
            firstParagraph = paragraphs.getFirst();
            clampedOffset = firstParagraph.offset();
        }

        int contentWidth = dimensions.getContentWidth();
        float bottomY = dimensions.height() - dimensions.bottomMargin();
        float currentY = dimensions.topMargin();
        float baseX = dimensions.leftMargin();

        PageLayout.Builder page = PageLayout.builder()
                .startOffset(clampedOffset)
                .endOffset(clampedOffset)
                .width(dimensions.width())
                .height(dimensions.height())
                .pageNumber(1);

        int paragraphIndex = firstParagraph.index();
        boolean firstOnPage = true;

        while (paragraphIndex < text.getParagraphCount() && currentY < bottomY) {
            ParagraphInfo paragraphInfo = text.getParagraphs().get(paragraphIndex);
            int paragraphStart = paragraphInfo.offset();
            int paragraphEnd = paragraphIndex + 1 < text.getParagraphCount()
                    ? text.getParagraphs().get(paragraphIndex + 1).offset()
                    : text.length();

            int fragmentStart = firstOnPage ? Math.max(clampedOffset, paragraphStart) : paragraphStart;
            firstOnPage = false;

            if (fragmentStart >= paragraphEnd) {
                paragraphIndex++;
                continue;
            }

            String paragraphText = text.getText(fragmentStart, paragraphEnd);
            if (paragraphText.isEmpty()) {
                paragraphIndex++;
                continue;
            }

            TextStyle style = paragraphInfo.style() != null ? paragraphInfo.style() : TextStyle.NORMAL;
            boolean firstLineOfParagraph = fragmentStart == paragraphStart;
            List<StyleSpan> fragmentSpans = text.getSpans(fragmentStart, paragraphEnd);

            ParagraphLayout fragment = layoutParagraphFragment(
                    paragraphText,
                    fragmentSpans,
                    fragmentStart,
                    style,
                    contentWidth,
                    baseX,
                    currentY,
                    bottomY,
                    paragraphIndex,
                    firstLineOfParagraph
            );

            if (fragment.isEmpty()) {
                // Захист від зациклення на дуже малому viewport.
                if (page.build().isEmpty()) {
                    int forcedEnd = Math.min(paragraphEnd, fragmentStart + 1);
                    page.endOffset(forcedEnd);
                }
                break;
            }

            page.addParagraph(fragment);
            page.endOffset(fragment.getEndOffset());
            currentY += fragment.getHeight();

            if (fragment.getEndOffset() < paragraphEnd) {
                // Поточний абзац продовжиться на наступній сторінці.
                break;
            }

            currentY += paragraphGap(style);
            paragraphIndex++;
        }

        PageLayout result = page.build();
        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed > 50) {
            log.debug("⏱️ Layout сторінки: {} мс, {} рядків, offset {}-{}",
                    elapsed, result.getLineCount(), result.getStartOffset(), result.getEndOffset());
        }
        return result;
    }

    private ParagraphLayout layoutParagraphFragment(
            String text,
            List<StyleSpan> spans,
            int absoluteStartOffset,
            TextStyle paragraphStyle,
            int contentWidth,
            float baseX,
            float startY,
            float bottomY,
            int paragraphIndex,
            boolean allowFirstLineIndent
    ) {
        List<LineLayout> lines = new ArrayList<>();
        if (text == null || text.isEmpty() || contentWidth <= 0) {
            return emptyParagraph(paragraphIndex, paragraphStyle, absoluteStartOffset,
                    baseX, startY, contentWidth);
        }

        float baseFontSize = resolveFontSize(paragraphStyle);
        float lineHeight = fontMetrics.getLineHeight(
                paragraphStyle, baseFontSize, (float) settings.lineSpacing());

        String trimmed = text.trim();

        // Порожній FB2-параграф має займати вертикальний простір і, головне,
        // просувати offset. Інакше книга з декоративними порожніми <p/> може
        // зациклити пагінацію на одному місці.
        if (trimmed.isEmpty()) {
            if (startY >= bottomY) {
                return emptyParagraph(paragraphIndex, paragraphStyle, absoluteStartOffset,
                        baseX, startY, contentWidth);
            }
            int consumed = Math.max(1, text.length());
            LineLayout blankLine = new LineLayout(
                    "", baseX, startY, 0, lineHeight, baseFontSize, paragraphIndex, 0,
                    paragraphStyle, absoluteStartOffset, consumed
            );
            return ParagraphLayout.builder()
                    .paragraphIndex(paragraphIndex)
                    .style(paragraphStyle)
                    .lines(List.of(blankLine))
                    .startOffset(absoluteStartOffset)
                    .endOffset(absoluteStartOffset + consumed)
                    .x(baseX).y(startY).width(contentWidth).height(lineHeight)
                    .build();
        }

        if (trimmed.startsWith("[IMAGE:") && trimmed.endsWith("]") && trimmed.indexOf(' ') < 0) {
            float imageLineHeight = Math.max(lineHeight, Math.min(bottomY - startY, baseFontSize * 9f));
            if (imageLineHeight > 0) {
                LineLayout imageLine = new LineLayout(
                        trimmed, baseX, startY, Math.min(contentWidth, baseFontSize * 12f), imageLineHeight,
                        baseFontSize, paragraphIndex, 0, paragraphStyle,
                        absoluteStartOffset, text.length()
                );
                return ParagraphLayout.builder()
                        .paragraphIndex(paragraphIndex)
                        .style(paragraphStyle)
                        .lines(List.of(imageLine))
                        .startOffset(absoluteStartOffset)
                        .endOffset(absoluteStartOffset + text.length())
                        .x(baseX).y(startY).width(contentWidth).height(imageLineHeight)
                        .build();
            }
        }

        float indent = allowFirstLineIndent && !paragraphStyle.isHeading()
                ? (float) (settings.firstLineIndent() * settings.fontSize())
                : 0f;

        int cursor = 0;
        int lineIndex = 0;
        float y = startY;

        while (cursor < text.length()) {
            cursor = skipLeadingLineWhitespace(text, cursor);
            if (cursor >= text.length()) {
                break;
            }

            // Якщо це перший рядок на абсолютно порожній сторінці, дозволяємо його
            // навіть коли viewport дуже низький — інакше позиція ніколи не зрушить.
            if (!lines.isEmpty() && y + lineHeight > bottomY) {
                break;
            }
            if (lines.isEmpty() && y >= bottomY) {
                break;
            }

            float lineIndent = lineIndex == 0 ? indent : 0f;
            float maxWidth = Math.max(1f, contentWidth - lineIndent);
            int rawEnd = findLineEnd(text, cursor, maxWidth, paragraphStyle, baseFontSize, spans);
            if (rawEnd <= cursor) {
                rawEnd = Math.min(text.length(), cursor + 1);
            }

            int nextCursor = rawEnd;
            while (nextCursor < text.length() && isSoftWhitespace(text.charAt(nextCursor))) {
                nextCursor++;
            }

            int displayEnd = rawEnd;
            while (displayEnd > cursor && Character.isWhitespace(text.charAt(displayEnd - 1))) {
                displayEnd--;
            }

            String lineText = text.substring(cursor, displayEnd);
            if (lineText.isEmpty() && rawEnd > cursor) {
                cursor = Math.max(nextCursor, rawEnd);
                continue;
            }

            float naturalLineWidth = measureRange(text, cursor, displayEnd, paragraphStyle, baseFontSize, spans);
            int consumedChars = Math.max(1, Math.max(nextCursor, rawEnd) - cursor);
            boolean justify = shouldJustifyLine(
                    text, cursor, displayEnd, rawEnd, maxWidth, naturalLineWidth, paragraphStyle);
            int spaces = justify ? countSpaces(text, cursor, displayEnd) : 0;
            float extraPerSpace = spaces > 0
                    ? Math.max(0f, (maxWidth - naturalLineWidth) / spaces)
                    : 0f;
            float lineWidth = justify ? maxWidth : naturalLineWidth;
            float lineX = resolveLineX(baseX, contentWidth, lineIndent, lineWidth);
            List<TextRunLayout> runs = buildVisualRuns(
                    text, cursor, displayEnd, absoluteStartOffset,
                    paragraphStyle, baseFontSize, spans, extraPerSpace
            );

            LineLayout line = new LineLayout(
                    lineText,
                    lineX,
                    y,
                    lineWidth,
                    lineHeight,
                    baseFontSize,
                    paragraphIndex,
                    lineIndex,
                    paragraphStyle,
                    absoluteStartOffset + cursor,
                    consumedChars,
                    runs
            );
            lines.add(line);

            cursor += consumedChars;
            y += lineHeight;
            lineIndex++;

            if (y >= bottomY) {
                break;
            }
        }

        long endOffset = absoluteStartOffset + cursor;
        float height = lines.size() * lineHeight;

        return ParagraphLayout.builder()
                .paragraphIndex(paragraphIndex)
                .style(paragraphStyle)
                .lines(lines)
                .startOffset(absoluteStartOffset)
                .endOffset(endOffset)
                .x(baseX)
                .y(startY)
                .width(contentWidth)
                .height(height)
                .build();
    }

    private ParagraphLayout emptyParagraph(
            int paragraphIndex,
            TextStyle style,
            int absoluteStartOffset,
            float baseX,
            float startY,
            int contentWidth
    ) {
        return ParagraphLayout.builder()
                .paragraphIndex(paragraphIndex)
                .style(style)
                .startOffset(absoluteStartOffset)
                .endOffset(absoluteStartOffset)
                .x(baseX)
                .y(startY)
                .width(contentWidth)
                .height(0)
                .build();
    }

    private int findLineEnd(
            String text,
            int start,
            float maxWidth,
            TextStyle paragraphStyle,
            float baseFontSize,
            List<StyleSpan> spans
    ) {
        float width = 0f;
        int lastBreak = -1;

        for (int i = start; i < text.length(); i++) {
            char c = text.charAt(i);

            if (c == '\n' || c == '\r') {
                return i + 1;
            }

            TextStyle style = styleAt(spans, i, paragraphStyle);
            float runFontSize = inlineFontSize(style, baseFontSize);
            float charWidth = fontMetrics.getCharWidth(c, style, runFontSize);
            if (width + charWidth > maxWidth) {
                if (i == start) {
                    return i + 1;
                }
                return lastBreak > start ? lastBreak : i;
            }

            width += charWidth;
            if (Character.isWhitespace(c) || c == '-' || c == '\u2010' || c == '\u2013') {
                lastBreak = i + 1;
            }
        }
        return text.length();
    }

    private float measureRange(
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

    /**
     * Створює runs тільки коли в рядку є inline-стиль, відмінний від стилю
     * параграфа. Це тримає звичайні художні книги максимально компактними.
     */
    private List<TextRunLayout> buildVisualRuns(
            String text,
            int start,
            int end,
            int absoluteStartOffset,
            TextStyle paragraphStyle,
            float baseFontSize,
            List<StyleSpan> spans,
            float extraPerSpace
    ) {
        boolean styleRunsNeeded = spans != null && !spans.isEmpty() &&
                hasDifferentStyle(spans, start, end, paragraphStyle);
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
            addRun(runs, text, runStart, end, absoluteStartOffset, runStyle, baseFontSize, x);
        }
        return List.copyOf(runs);
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

    private boolean shouldJustifyLine(
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

    private int countSpaces(String text, int start, int end) {
        int count = 0;
        for (int i = start; i < end; i++) {
            if (text.charAt(i) == ' ') count++;
        }
        return count;
    }

    private boolean hasDifferentStyle(List<StyleSpan> spans, int start, int end, TextStyle paragraphStyle) {
        for (StyleSpan span : spans) {
            if (span.end() <= start) continue;
            if (span.start() >= end) break;
            if (span.style() != null && span.style() != paragraphStyle) {
                return true;
            }
        }
        return false;
    }

    /** spans відносні до початку paragraph fragment. */
    private TextStyle styleAt(List<StyleSpan> spans, int offset, TextStyle fallback) {
        if (spans == null || spans.isEmpty()) {
            return fallback;
        }

        // Список відсортований за start (TextStorage додає spans послідовно).
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
            if (offset < span.end() && span.style() != null) {
                return span.style();
            }
        }
        return fallback;
    }

    private float inlineFontSize(TextStyle style, float baseFontSize) {
        if (style == TextStyle.SUPERSCRIPT || style == TextStyle.SUBSCRIPT) {
            return baseFontSize * 0.76f;
        }
        return baseFontSize;
    }

    private int skipLeadingLineWhitespace(String text, int cursor) {
        int i = cursor;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t' || c == ' ') {
                i++;
            } else {
                break;
            }
        }
        return i;
    }

    private boolean isSoftWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }

    private float resolveLineX(float baseX, int contentWidth, float lineIndent, float lineWidth) {
        String alignment = settings.alignment() == null
                ? "left"
                : settings.alignment().toLowerCase(Locale.ROOT);
        return switch (alignment) {
            case "center" -> baseX + Math.max(0f, (contentWidth - lineWidth) / 2f);
            case "right" -> baseX + Math.max(0f, contentWidth - lineWidth);
            default -> baseX + lineIndent; // justify додається renderer-ом окремо у майбутньому
        };
    }

    private float resolveFontSize(TextStyle style) {
        float base = (float) settings.fontSize();
        if (style == null) {
            return base;
        }
        return switch (style) {
            case HEADING_1 -> base * 1.55f;
            case HEADING_2 -> base * 1.35f;
            case HEADING_3 -> base * 1.20f;
            case HEADING_4, HEADING_5, HEADING_6 -> base * 1.10f;
            case NOTE, FOOTNOTE -> base * 0.90f;
            default -> base;
        };
    }

    private float paragraphGap(TextStyle style) {
        double multiplier = style != null && style.isHeading() ? 0.65 : 0.25;
        return (float) Math.max(0, settings.paragraphSpacing() * settings.fontSize() * multiplier);
    }

    public void clearCache() {
        if (fontMetrics instanceof FontMetricsProviderImpl impl) {
            impl.clearCache();
        }
    }

    public String getCacheStats() {
        if (fontMetrics instanceof FontMetricsProviderImpl impl) {
            return impl.getCacheStats();
        }
        return "TextLayoutEngine";
    }
}
