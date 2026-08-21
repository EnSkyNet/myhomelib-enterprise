package com.myhomelibcorp.reader.api;

import java.util.List;

public interface TextStorage {

    String getFullText();

    String getText(int start, int end);

    TextFragment getFragment(int start, int end);

    int length();

    List<StyleSpan> getSpans(int start, int end);

    List<ParagraphInfo> getParagraphs();

    ParagraphInfo findParagraphAt(int offset);

    int getParagraphCount();
}