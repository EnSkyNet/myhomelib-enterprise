package com.myhomelibcorp.reader.core.text;

import com.myhomelibcorp.reader.api.ParagraphInfo;
import com.myhomelibcorp.reader.api.StyleSpan;
import com.myhomelibcorp.reader.api.TextFragment;
import com.myhomelibcorp.reader.api.TextStorage;
import com.myhomelibcorp.reader.api.TextStyle;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Компактна реалізація TextStorage.
 * Текст зберігається в StringBuilder, стилі та параграфи — у списках.
 */
public class TextStorageImpl implements TextStorage {

    private final StringBuilder text = new StringBuilder();
    private final List<StyleSpan> spans = new ArrayList<>();
    private final List<ParagraphInfo> paragraphs = new ArrayList<>();

    private transient int lastParagraphIndex = -1;
    private transient int lastParagraphOffset = -1;

    public TextStorageImpl() {
    }

    public int append(String textPart, TextStyle style) {
        if (textPart == null || textPart.isEmpty()) {
            return text.length();
        }
        int start = text.length();
        text.append(textPart);
        if (style != null && style != TextStyle.NORMAL) {
            spans.add(new StyleSpan(start, text.length(), style));
        }
        return start;
    }

    public int appendParagraph(String textPart, TextStyle style) {
        int start = append(textPart, style);
        paragraphs.add(new ParagraphInfo(start, paragraphs.size(), style != null ? style : TextStyle.NORMAL));
        return start;
    }

    public int startParagraph(TextStyle style) {
        int offset = text.length();
        paragraphs.add(new ParagraphInfo(offset, paragraphs.size(), style != null ? style : TextStyle.NORMAL));
        return offset;
    }

    public void endParagraph() {
        // Нічого не робимо
    }

    public void addSpan(int start, int end, TextStyle style) {
        if (start < end && style != null && style != TextStyle.NORMAL) {
            spans.add(new StyleSpan(start, end, style));
        }
    }

    @Override
    public String getFullText() {
        return text.toString();
    }

    @Override
    public String getText(int start, int end) {
        if (start < 0) start = 0;
        if (end > text.length()) end = text.length();
        if (start >= end) return "";
        return text.substring(start, end);
    }

    @Override
    public TextFragment getFragment(int start, int end) {
        if (start < 0) start = 0;
        if (end > text.length()) end = text.length();
        if (start >= end) {
            return new TextFragment("", List.of());
        }
        String fragmentText = text.substring(start, end);
        List<StyleSpan> fragmentSpans = getSpans(start, end);
        return new TextFragment(fragmentText, fragmentSpans);
    }

    @Override
    public int length() {
        return text.length();
    }

    @Override
    public List<StyleSpan> getSpans(int start, int end) {
        if (spans.isEmpty() || start >= end) {
            return List.of();
        }
        List<StyleSpan> result = new ArrayList<>();
        for (StyleSpan span : spans) {
            if (span.end() > start && span.start() < end) {
                int adjustedStart = Math.max(span.start(), start);
                int adjustedEnd = Math.min(span.end(), end);
                if (adjustedStart < adjustedEnd) {
                    result.add(new StyleSpan(adjustedStart - start, adjustedEnd - start, span.style()));
                }
            }
        }
        return result;
    }

    @Override
    public List<ParagraphInfo> getParagraphs() {
        return Collections.unmodifiableList(paragraphs);
    }

    @Override
    public ParagraphInfo findParagraphAt(int offset) {
        if (paragraphs.isEmpty()) {
            return null;
        }

        if (lastParagraphIndex >= 0 && lastParagraphIndex < paragraphs.size()) {
            ParagraphInfo cached = paragraphs.get(lastParagraphIndex);
            int nextOffset = lastParagraphIndex + 1 < paragraphs.size()
                    ? paragraphs.get(lastParagraphIndex + 1).offset()
                    : text.length();
            if (offset >= cached.offset() && offset < nextOffset) {
                return cached;
            }
        }

        int lo = 0;
        int hi = paragraphs.size() - 1;
        int best = 0;

        while (lo <= hi) {
            int mid = (lo + hi) / 2;
            ParagraphInfo p = paragraphs.get(mid);
            if (p.offset() <= offset) {
                best = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }

        if (best < paragraphs.size()) {
            lastParagraphIndex = best;
            lastParagraphOffset = offset;
            return paragraphs.get(best);
        }

        return null;
    }

    @Override
    public int getParagraphCount() {
        return paragraphs.size();
    }

    public void clear() {
        text.setLength(0);
        spans.clear();
        paragraphs.clear();
        lastParagraphIndex = -1;
        lastParagraphOffset = -1;
    }

    public TextStorageImpl copy() {
        TextStorageImpl copy = new TextStorageImpl();
        copy.text.append(this.text);
        copy.spans.addAll(this.spans);
        copy.paragraphs.addAll(this.paragraphs);
        return copy;
    }

    public long estimateMemoryUsage() {
        long size = text.length() * 2L;
        size += spans.size() * 32L;
        size += paragraphs.size() * 24L;
        return size;
    }

    @Override
    public String toString() {
        return "TextStorageImpl{" +
                "chars=" + text.length() +
                ", spans=" + spans.size() +
                ", paragraphs=" + paragraphs.size() +
                '}';
    }
}