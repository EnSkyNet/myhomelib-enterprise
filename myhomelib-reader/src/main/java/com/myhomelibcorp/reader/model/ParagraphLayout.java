package com.myhomelibcorp.reader.model;

import com.myhomelibcorp.reader.api.TextStyle;

import java.util.ArrayList;
import java.util.List;

/**
 * Розкладка одного параграфа.
 */
public class ParagraphLayout {

    private final int paragraphIndex;
    private final TextStyle style;
    private final List<LineLayout> lines;
    private final long startOffset;
    private final long endOffset;
    private final float x;
    private final float y;
    private final float width;
    private final float height;

    private ParagraphLayout(Builder builder) {
        this.paragraphIndex = builder.paragraphIndex;
        this.style = builder.style;
        this.lines = builder.lines != null ? List.copyOf(builder.lines) : List.of();
        this.startOffset = builder.startOffset;
        this.endOffset = builder.endOffset;
        this.x = builder.x;
        this.y = builder.y;
        this.width = builder.width;
        this.height = builder.height;
    }

    public int getParagraphIndex() {
        return paragraphIndex;
    }

    public TextStyle getStyle() {
        return style;
    }

    public List<LineLayout> getLines() {
        return lines;
    }

    public long getStartOffset() {
        return startOffset;
    }

    public long getEndOffset() {
        return endOffset;
    }

    public float getX() {
        return x;
    }

    public float getY() {
        return y;
    }

    public float getWidth() {
        return width;
    }

    public float getHeight() {
        return height;
    }

    public int getLineCount() {
        return lines.size();
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private int paragraphIndex;
        private TextStyle style = TextStyle.NORMAL;
        private List<LineLayout> lines = new ArrayList<>();
        private long startOffset;
        private long endOffset;
        private float x;
        private float y;
        private float width;
        private float height;

        public Builder paragraphIndex(int paragraphIndex) {
            this.paragraphIndex = paragraphIndex;
            return this;
        }

        public Builder style(TextStyle style) {
            this.style = style;
            return this;
        }

        public Builder lines(List<LineLayout> lines) {
            this.lines = lines != null ? new ArrayList<>(lines) : new ArrayList<>();
            return this;
        }

        public Builder addLine(LineLayout line) {
            if (line != null) {
                this.lines.add(line);
            }
            return this;
        }

        public Builder startOffset(long startOffset) {
            this.startOffset = startOffset;
            return this;
        }

        public Builder endOffset(long endOffset) {
            this.endOffset = endOffset;
            return this;
        }

        public Builder x(float x) {
            this.x = x;
            return this;
        }

        public Builder y(float y) {
            this.y = y;
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

        public ParagraphLayout build() {
            return new ParagraphLayout(this);
        }
    }

    @Override
    public String toString() {
        return "ParagraphLayout{" +
                "index=" + paragraphIndex +
                ", lines=" + lines.size() +
                ", offset=" + startOffset + "-" + endOffset +
                ", y=" + y +
                ", height=" + height +
                '}';
    }
}