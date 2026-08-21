package com.myhomelibcorp.reader.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Розкладка однієї сторінки.
 */
public class PageLayout {

    private final long startOffset;
    private final long endOffset;
    private final List<ParagraphLayout> paragraphs;
    private final List<LineLayout> lines;
    private final float width;
    private final float height;
    private final int pageNumber;

    private PageLayout(Builder builder) {
        this.startOffset = builder.startOffset;
        this.endOffset = builder.endOffset;
        this.paragraphs = builder.paragraphs != null ? List.copyOf(builder.paragraphs) : List.of();
        this.lines = builder.lines != null ? List.copyOf(builder.lines) : List.of();
        this.width = builder.width;
        this.height = builder.height;
        this.pageNumber = builder.pageNumber;
    }

    public long getStartOffset() {
        return startOffset;
    }

    public long getEndOffset() {
        return endOffset;
    }

    public List<ParagraphLayout> getParagraphs() {
        return paragraphs;
    }

    public List<LineLayout> getLines() {
        return lines;
    }

    public float getWidth() {
        return width;
    }

    public float getHeight() {
        return height;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public int getParagraphCount() {
        return paragraphs.size();
    }

    public int getLineCount() {
        return lines.size();
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public long length() {
        return endOffset - startOffset;
    }

    public static PageLayout empty() {
        return new Builder().build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private long startOffset;
        private long endOffset;
        private List<ParagraphLayout> paragraphs = new ArrayList<>();
        private List<LineLayout> lines = new ArrayList<>();
        private float width;
        private float height;
        private int pageNumber;

        public Builder startOffset(long startOffset) {
            this.startOffset = startOffset;
            return this;
        }

        public Builder endOffset(long endOffset) {
            this.endOffset = endOffset;
            return this;
        }

        public Builder paragraphs(List<ParagraphLayout> paragraphs) {
            this.paragraphs = paragraphs != null ? new ArrayList<>(paragraphs) : new ArrayList<>();
            return this;
        }

        public Builder addParagraph(ParagraphLayout paragraph) {
            if (paragraph != null && !paragraph.isEmpty()) {
                this.paragraphs.add(paragraph);
                this.lines.addAll(paragraph.getLines());
            }
            return this;
        }

        public Builder lines(List<LineLayout> lines) {
            this.lines = lines != null ? new ArrayList<>(lines) : new ArrayList<>();
            return this;
        }

        public Builder width(float width) {
            this.width = width;
            return this;
        }

        public Builder height(float height) {
            this.height = height;
            return this;
        }

        public Builder pageNumber(int pageNumber) {
            this.pageNumber = pageNumber;
            return this;
        }

        public PageLayout build() {
            if (paragraphs.isEmpty() && !lines.isEmpty()) {
                rebuildParagraphs();
            }
            return new PageLayout(this);
        }

        private void rebuildParagraphs() {
            if (lines.isEmpty()) {
                return;
            }
            Map<Integer, List<LineLayout>> grouped = new LinkedHashMap<>();
            for (LineLayout line : lines) {
                grouped.computeIfAbsent(line.paragraphIndex(), k -> new ArrayList<>()).add(line);
            }
            for (Map.Entry<Integer, List<LineLayout>> entry : grouped.entrySet()) {
                List<LineLayout> paragraphLines = entry.getValue();
                if (!paragraphLines.isEmpty()) {
                    LineLayout first = paragraphLines.get(0);
                    ParagraphLayout paragraph = ParagraphLayout.builder()
                            .paragraphIndex(entry.getKey())
                            .style(first.style())
                            .lines(paragraphLines)
                            .startOffset(first.textOffset())
                            .endOffset(paragraphLines.get(paragraphLines.size() - 1).textOffset())
                            .x(first.x())
                            .y(first.y())
                            .width((float) paragraphLines.stream().mapToDouble(LineLayout::width).max().orElse(0))
                            .height((float) paragraphLines.stream().mapToDouble(LineLayout::height).sum())
                            .build();
                    paragraphs.add(paragraph);
                }
            }
        }
    }

    @Override
    public String toString() {
        return "PageLayout{" +
                "page=" + pageNumber +
                ", lines=" + lines.size() +
                ", paragraphs=" + paragraphs.size() +
                ", offset=" + startOffset + "-" + endOffset +
                ", size=" + width + "x" + height +
                '}';
    }
}