package com.myhomelibcorp.reader.render.pdf;

public record PdfPageSize(double widthPoints, double heightPoints) {
    public PdfPageSize {
        if (!Double.isFinite(widthPoints) || !Double.isFinite(heightPoints)
                || !(widthPoints > 0) || !(heightPoints > 0)) {
            throw new IllegalArgumentException("invalid PDF page size");
        }
    }
}
