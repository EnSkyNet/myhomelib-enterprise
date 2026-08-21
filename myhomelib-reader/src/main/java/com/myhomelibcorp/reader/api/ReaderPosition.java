package com.myhomelibcorp.reader.api;

public record ReaderPosition(
        int chapterIndex,
        long textOffset,
        int paragraphIndex,
        int charOffset
) implements Comparable<ReaderPosition> {

    public static ReaderPosition start() {
        return new ReaderPosition(0, 0, 0, 0);
    }

    public static ReaderPosition end(long totalTextLength) {
        return new ReaderPosition(0, totalTextLength, 0, 0);
    }

    public boolean isStart() {
        return chapterIndex == 0 && textOffset == 0 && paragraphIndex == 0 && charOffset == 0;
    }

    public boolean isEnd(long totalTextLength) {
        return textOffset >= totalTextLength;
    }

    public ReaderPosition withTextOffset(long newOffset) {
        return new ReaderPosition(chapterIndex, newOffset, paragraphIndex, charOffset);
    }

    public ReaderPosition withChapter(int newChapterIndex) {
        return new ReaderPosition(newChapterIndex, textOffset, paragraphIndex, charOffset);
    }

    public ReaderPosition withParagraph(int newParagraphIndex) {
        return new ReaderPosition(chapterIndex, textOffset, newParagraphIndex, charOffset);
    }

    public ReaderPosition withCharOffset(int newCharOffset) {
        return new ReaderPosition(chapterIndex, textOffset, paragraphIndex, newCharOffset);
    }

    public boolean isValid(long totalTextLength) {
        return chapterIndex >= 0 &&
                textOffset >= 0 &&
                textOffset <= totalTextLength &&
                paragraphIndex >= 0 &&
                charOffset >= 0;
    }

    public ReaderPosition snapToParagraph() {
        return new ReaderPosition(chapterIndex, textOffset, paragraphIndex, 0);
    }

    public double getPercent(long totalTextLength) {
        if (totalTextLength <= 0) {
            return 0.0;
        }
        return Math.min(100.0, (double) textOffset / totalTextLength * 100.0);
    }

    @Override
    public int compareTo(ReaderPosition other) {
        if (other == null) {
            return 1;
        }
        return Long.compare(this.textOffset, other.textOffset);
    }

    public boolean isBefore(ReaderPosition other) {
        return this.textOffset < other.textOffset;
    }

    public boolean isAfter(ReaderPosition other) {
        return this.textOffset > other.textOffset;
    }

    public long distanceTo(ReaderPosition other) {
        return Math.abs(this.textOffset - other.textOffset);
    }

    public String serialize() {
        return chapterIndex + ":" + textOffset + ":" + paragraphIndex + ":" + charOffset;
    }

    public static ReaderPosition parse(String value) {
        if (value == null || value.isBlank()) {
            return start();
        }
        try {
            String[] parts = value.split(":");
            if (parts.length == 4) {
                int ch = Integer.parseInt(parts[0]);
                long offset = Long.parseLong(parts[1]);
                int para = Integer.parseInt(parts[2]);
                int chOffset = Integer.parseInt(parts[3]);
                return new ReaderPosition(ch, offset, para, chOffset);
            }
            if (parts.length == 1) {
                return new ReaderPosition(0, Long.parseLong(parts[0]), 0, 0);
            }
        } catch (NumberFormatException e) {
            // ignore
        }
        return start();
    }

    @Override
    public String toString() {
        return String.format("ReaderPosition{ch=%d, offset=%d, para=%d, char=%d}",
                chapterIndex, textOffset, paragraphIndex, charOffset);
    }
}