package com.myhomelibcorp.application.tts;

public record TtsSentence(int index, long startOffset, long endOffset, String text) { }
