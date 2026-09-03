package com.myhomelibcorp.reader.api;

/**
 * Structured typography/color override for one semantic Reader element.
 * Empty/null values inherit the global Reader setting/theme. A positive fontSize
 * is absolute; otherwise fontScale is applied to the global Reader font size.
 */
public record ReaderElementStyle(
        String fontFamily,
        Double fontSize,
        double fontScale,
        String fontWeight,
        String color,
        String alignment,
        Double spacingBefore,
        Double spacingAfter
) {
    public ReaderElementStyle {
        fontFamily = clean(fontFamily);
        fontWeight = clean(fontWeight);
        color = normalizeColor(color);
        alignment = clean(alignment);
        if (fontSize != null && (!Double.isFinite(fontSize) || fontSize <= 0)) fontSize = null;
        if (!Double.isFinite(fontScale) || fontScale <= 0) fontScale = 1.0;
        if (spacingBefore != null && (!Double.isFinite(spacingBefore) || spacingBefore < 0)) spacingBefore = null;
        if (spacingAfter != null && (!Double.isFinite(spacingAfter) || spacingAfter < 0)) spacingAfter = null;
    }

    public static ReaderElementStyle inherited(double scale) {
        return new ReaderElementStyle("", null, scale, "", "", "", null, null);
    }

    public double resolveFontSize(double baseSize) {
        return fontSize != null ? fontSize : baseSize * fontScale;
    }

    public ReaderElementStyle withColor(String value) {
        return new ReaderElementStyle(fontFamily, fontSize, fontScale, fontWeight, value, alignment, spacingBefore, spacingAfter);
    }

    private static String normalizeColor(String value) {
        String clean = clean(value);
        return clean.matches("#[0-9a-fA-F]{6}([0-9a-fA-F]{2})?") ? clean : "";
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
