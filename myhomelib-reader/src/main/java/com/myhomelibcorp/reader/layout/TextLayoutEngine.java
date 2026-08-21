package com.myhomelibcorp.reader.layout;

import com.myhomelibcorp.reader.api.*;
import com.myhomelibcorp.reader.model.LineLayout;
import com.myhomelibcorp.reader.model.PageLayout;
import com.myhomelibcorp.reader.model.ParagraphLayout;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

@Slf4j
public class TextLayoutEngine {

    private final FontMetricsProvider fontMetrics;
    private final ReaderSettings settings;

    public TextLayoutEngine(FontMetricsProvider fontMetrics, ReaderSettings settings) {
        this.fontMetrics = fontMetrics;
        this.settings = settings;
    }

    public PageLayout layoutPage(ReaderDocument document, long textOffset, PageDimensions dimensions) {
        if (document == null || dimensions == null || !dimensions.isValid()) {
            return PageLayout.empty();
        }

        TextStorage text = document.text();
        if (text == null || text.length() == 0) {
            return PageLayout.empty();
        }

        long startTime = System.currentTimeMillis();

        int contentWidth = dimensions.getContentWidth();
        int contentHeight = dimensions.getContentHeight();

        ParagraphInfo paragraph = text.findParagraphAt((int) textOffset);
        if (paragraph == null) {
            List<ParagraphInfo> paragraphs = text.getParagraphs();
            if (paragraphs.isEmpty()) {
                return PageLayout.empty();
            }
            paragraph = paragraphs.get(0);
        }

        PageLayout.Builder pageBuilder = PageLayout.builder()
                .startOffset(paragraph.offset())
                .width(dimensions.width())
                .height(dimensions.height())
                .pageNumber(1);

        float currentX = dimensions.leftMargin();
        float currentY = dimensions.topMargin();
        int paragraphIndex = paragraph.index();

        while (paragraphIndex < text.getParagraphCount() && currentY < dimensions.height() - dimensions.bottomMargin()) {
            ParagraphInfo currentParagraph = text.getParagraphs().get(paragraphIndex);
            int startOffset = currentParagraph.offset();

            int endOffset;
            if (paragraphIndex + 1 < text.getParagraphCount()) {
                endOffset = text.getParagraphs().get(paragraphIndex + 1).offset();
            } else {
                endOffset = text.length();
            }

            if (startOffset >= endOffset) {
                paragraphIndex++;
                continue;
            }

            String paragraphText = text.getText(startOffset, endOffset);
            if (paragraphText == null || paragraphText.isBlank()) {
                paragraphIndex++;
                continue;
            }

            TextStyle paraStyle = currentParagraph.style() != null ? currentParagraph.style() : TextStyle.NORMAL;
            float firstLineIndent = (float) (settings.firstLineIndent() * settings.fontSize());

            List<LineLayout> lines = layoutParagraph(
                    paragraphText,
                    startOffset,
                    paraStyle,
                    contentWidth - (int) firstLineIndent,
                    currentX + firstLineIndent,
                    currentY,
                    paragraphIndex
            );

            if (lines.isEmpty()) {
                paragraphIndex++;
                continue;
            }

            float paragraphHeight = (float) lines.stream().mapToDouble(LineLayout::height).sum();
            if (currentY + paragraphHeight > dimensions.height() - dimensions.bottomMargin()) {
                float availableHeight = dimensions.height() - dimensions.bottomMargin() - currentY;
                int linesToAdd = 0;
                float accumulatedHeight = 0;

                for (LineLayout line : lines) {
                    if (accumulatedHeight + line.height() <= availableHeight) {
                        accumulatedHeight += line.height();
                        linesToAdd++;
                    } else {
                        break;
                    }
                }

                if (linesToAdd > 0) {
                    List<LineLayout> partialLines = lines.subList(0, linesToAdd);
                    ParagraphLayout partialParagraph = ParagraphLayout.builder()
                            .paragraphIndex(paragraphIndex)
                            .style(paraStyle)
                            .lines(partialLines)
                            .startOffset(partialLines.get(0).textOffset())
                            .endOffset(partialLines.get(partialLines.size() - 1).textOffset() +
                                    partialLines.get(partialLines.size() - 1).charLength())
                            .x(currentX)
                            .y(currentY)
                            .width(contentWidth)
                            .height(accumulatedHeight)
                            .build();

                    pageBuilder.addParagraph(partialParagraph);
                    pageBuilder.endOffset(partialParagraph.getEndOffset());
                }
                break;
            }

            ParagraphLayout paragraphLayout = ParagraphLayout.builder()
                    .paragraphIndex(paragraphIndex)
                    .style(paraStyle)
                    .lines(lines)
                    .startOffset(startOffset)
                    .endOffset(endOffset)
                    .x(currentX)
                    .y(currentY)
                    .width(contentWidth)
                    .height(paragraphHeight)
                    .build();

            pageBuilder.addParagraph(paragraphLayout);
            pageBuilder.endOffset(endOffset);

            currentY += paragraphHeight;
            paragraphIndex++;
        }

        PageLayout result = pageBuilder.build();

        long elapsed = System.currentTimeMillis() - startTime;
        if (elapsed > 50) {
            log.debug("⏱️ Layout сторінки зайняв {} мс, {} рядків, {} параграфів",
                    elapsed, result.getLineCount(), result.getParagraphCount());
        }

        return result;
    }

    private List<LineLayout> layoutParagraph(
            String text,
            int startOffset,
            TextStyle style,
            int maxWidth,
            float x,
            float y,
            int paragraphIndex
    ) {
        List<LineLayout> lines = new ArrayList<>();
        if (text == null || text.isEmpty()) {
            return lines;
        }

        String[] words = text.split("(?<=\\s)|(?=\\s)");
        if (words.length == 0) {
            words = new String[]{text};
        }

        float fontSize = (float) settings.fontSize();
        float lineHeight = fontMetrics.getLineHeight(style, fontSize, (float) settings.lineSpacing());

        if (style.isHeading()) {
            int level = style.getHeadingLevel();
            fontSize *= (1.8f - (level - 1) * 0.15f);
            lineHeight = fontMetrics.getLineHeight(style, fontSize, (float) settings.lineSpacing());
        }

        List<String> currentLine = new ArrayList<>();
        float currentWidth = 0;
        int lineStartOffset = startOffset;
        int lineIndex = 0;

        for (String word : words) {
            float wordWidth = fontMetrics.getStringWidth(word, style, fontSize);
            float spaceWidth = fontMetrics.getSpaceWidth(style, fontSize);

            if (currentWidth + wordWidth > maxWidth && !currentLine.isEmpty()) {
                String lineText = String.join("", currentLine);
                float lineY = y + lineIndex * lineHeight;

                LineLayout line = new LineLayout(
                        lineText,
                        x,
                        lineY,
                        currentWidth,
                        lineHeight,
                        paragraphIndex,
                        lineIndex,
                        style,
                        lineStartOffset,
                        lineText.length()
                );
                lines.add(line);

                currentLine.clear();
                currentLine.add(word);
                currentWidth = wordWidth;
                lineStartOffset = startOffset + lineText.length() + 1;
                lineIndex++;
            } else {
                if (!currentLine.isEmpty()) {
                    currentWidth += spaceWidth;
                }
                currentLine.add(word);
                currentWidth += wordWidth;
            }
        }

        if (!currentLine.isEmpty()) {
            String lineText = String.join("", currentLine);
            float lineY = y + lineIndex * lineHeight;

            LineLayout line = new LineLayout(
                    lineText,
                    x,
                    lineY,
                    currentWidth,
                    lineHeight,
                    paragraphIndex,
                    lineIndex,
                    style,
                    lineStartOffset,
                    lineText.length()
            );
            lines.add(line);
        }

        if (lines.isEmpty()) {
            LineLayout emptyLine = new LineLayout(
                    "",
                    x,
                    y,
                    0,
                    lineHeight,
                    paragraphIndex,
                    0,
                    style,
                    startOffset,
                    0
            );
            lines.add(emptyLine);
        }

        return lines;
    }

    public void clearCache() {
        // Кеш видалено для простоти
    }

    public String getCacheStats() {
        return "TextLayoutEngine: no cache";
    }
}