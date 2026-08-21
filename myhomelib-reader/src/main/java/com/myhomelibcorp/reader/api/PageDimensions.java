package com.myhomelibcorp.reader.api;

public record PageDimensions(
        int width,
        int height,
        int leftMargin,
        int rightMargin,
        int topMargin,
        int bottomMargin
) {
    public static PageDimensions of(int width, int height) {
        return new PageDimensions(width, height, 30, 30, 20, 20);
    }

    public static PageDimensions of(int width, int height, int margin) {
        return new PageDimensions(width, height, margin, margin, margin, margin);
    }

    public int getContentWidth() {
        return Math.max(0, width - leftMargin - rightMargin);
    }

    public int getContentHeight() {
        return Math.max(0, height - topMargin - bottomMargin);
    }

    public boolean isValid() {
        return width > 0 && height > 0 && getContentWidth() > 0 && getContentHeight() > 0;
    }

    public PageDimensions scale(double factor) {
        return new PageDimensions(
                (int) (width * factor),
                (int) (height * factor),
                (int) (leftMargin * factor),
                (int) (rightMargin * factor),
                (int) (topMargin * factor),
                (int) (bottomMargin * factor)
        );
    }
}