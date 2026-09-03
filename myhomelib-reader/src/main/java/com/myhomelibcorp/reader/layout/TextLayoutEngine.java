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

/**
 * Посторінковий reflow layout. Працює тільки з поточною сторінкою, тому не
 * будує повну карту сторінок книги в пам'яті.
 *
 * Inline FB2-стилі зберігаються у компактних TextRunLayout тільки для тих
 * рядків, де вони реально є. Звичайний текст не отримує додаткових run-об'єктів.
 */
@Slf4j
public class TextLayoutEngine {

    private static final int MAX_LAYOUT_FRAGMENT_CHARS = 262_144;

    private FontMetricsProvider fontMetrics;
    private ReaderSettings settings;
    private final TextLineLayoutSupport lineSupport;

    public TextLayoutEngine(FontMetricsProvider fontMetrics, ReaderSettings settings) {
        this.fontMetrics = fontMetrics;
        this.settings = settings != null ? settings : ReaderSettings.defaultSettings();
        this.lineSupport = new TextLineLayoutSupport(fontMetrics, this.settings);
    }

    public void updateSettings(ReaderSettings newSettings) {
        if (newSettings == null) {
            return;
        }
        this.settings = newSettings;
        this.fontMetrics = this.fontMetrics.withSettings(newSettings);
        this.lineSupport.update(this.fontMetrics, this.settings);
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

        List<ParagraphInfo> paragraphs = text.getParagraphs();
        ParagraphInfo firstParagraph = text.findParagraphAt(clampedOffset);
        if (firstParagraph == null) {
            if (paragraphs.isEmpty()) return PageLayout.empty();
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
            ParagraphInfo paragraphInfo = paragraphs.get(paragraphIndex);
            int paragraphStart = paragraphInfo.offset();
            int paragraphEnd = paragraphIndex + 1 < paragraphs.size()
                    ? paragraphs.get(paragraphIndex + 1).offset()
                    : text.length();

            int fragmentStart = firstOnPage ? Math.max(clampedOffset, paragraphStart) : paragraphStart;
            firstOnPage = false;

            if (fragmentStart >= paragraphEnd) {
                paragraphIndex++;
                continue;
            }

            // A malformed/converted ebook may contain a multi-megabyte single
            // paragraph. A page never needs the whole tail at once, so cap the
            // temporary substring and style projection used by one layout pass.
            int layoutEnd = Math.min(paragraphEnd, fragmentStart + MAX_LAYOUT_FRAGMENT_CHARS);
            String paragraphText = text.getText(fragmentStart, layoutEnd);
            if (paragraphText.isEmpty()) {
                paragraphIndex++;
                continue;
            }

            TextStyle style = paragraphInfo.style() != null ? paragraphInfo.style() : TextStyle.NORMAL;
            boolean firstLineOfParagraph = fragmentStart == paragraphStart;
            if (firstLineOfParagraph) {
                float spacingBefore = lineSupport.paragraphSpacingBefore(style);
                if (spacingBefore > 0f) {
                    if (currentY + spacingBefore < bottomY) {
                        currentY += spacingBefore;
                    } else if (!page.build().isEmpty()) {
                        break;
                    }
                    // If the page is still empty, ignore an oversized semantic
                    // top spacing so pagination always consumes text and advances.
                }
            }
            List<StyleSpan> fragmentSpans = text.getSpans(fragmentStart, layoutEnd);

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
                    firstLineOfParagraph,
                    document.metadata() != null ? document.metadata().language() : ""
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

            currentY += lineSupport.paragraphGap(style);
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
            boolean allowFirstLineIndent,
            String language
    ) {
        List<LineLayout> lines = new ArrayList<>();
        if (text == null || text.isEmpty() || contentWidth <= 0) {
            return emptyParagraph(paragraphIndex, paragraphStyle, absoluteStartOffset,
                    baseX, startY, contentWidth);
        }

        float baseFontSize = lineSupport.resolveFontSize(paragraphStyle);
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
            cursor = lineSupport.skipLeadingLineWhitespace(text, cursor);
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
            TextLineLayoutSupport.LineBreak lineBreak = lineSupport.findLineEnd(text, cursor, maxWidth, paragraphStyle, baseFontSize, spans, language);
            int rawEnd = lineBreak.end();
            boolean hyphenated = lineBreak.hyphenated();
            if (rawEnd <= cursor) {
                rawEnd = Math.min(text.length(), cursor + 1);
                hyphenated = false;
            }

            int nextCursor = rawEnd;
            while (nextCursor < text.length() && lineSupport.isSoftWhitespace(text.charAt(nextCursor))) {
                nextCursor++;
            }

            int displayEnd = rawEnd;
            while (displayEnd > cursor && Character.isWhitespace(text.charAt(displayEnd - 1))) {
                displayEnd--;
            }

            String lineText = text.substring(cursor, displayEnd) + (hyphenated ? "‐" : "");
            if (lineText.isEmpty() && rawEnd > cursor) {
                cursor = Math.max(nextCursor, rawEnd);
                continue;
            }

            float naturalLineWidth = lineSupport.measureRange(text, cursor, displayEnd, paragraphStyle, baseFontSize, spans);
            if (hyphenated) {
                TextStyle hyphenStyle = lineSupport.styleAt(spans, Math.max(cursor, displayEnd - 1), paragraphStyle);
                naturalLineWidth += fontMetrics.getCharWidth('‐', hyphenStyle, lineSupport.inlineFontSize(hyphenStyle, baseFontSize));
            }
            int consumedChars = Math.max(1, Math.max(nextCursor, rawEnd) - cursor);
            boolean justify = !hyphenated && lineSupport.shouldJustifyLine(
                    text, cursor, displayEnd, rawEnd, maxWidth, naturalLineWidth, paragraphStyle);
            int spaces = justify ? lineSupport.countSpaces(text, cursor, displayEnd) : 0;
            float extraPerSpace = spaces > 0
                    ? Math.max(0f, (maxWidth - naturalLineWidth) / spaces)
                    : 0f;
            float lineWidth = justify ? maxWidth : naturalLineWidth;
            float lineX = lineSupport.resolveLineX(baseX, contentWidth, lineIndent, lineWidth, paragraphStyle);
            List<TextRunLayout> runs = lineSupport.buildVisualRuns(
                    text, cursor, displayEnd, absoluteStartOffset,
                    paragraphStyle, baseFontSize, spans, extraPerSpace, hyphenated
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
