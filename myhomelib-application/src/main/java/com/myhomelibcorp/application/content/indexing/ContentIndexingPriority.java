package com.myhomelibcorp.application.content.indexing;

public enum ContentIndexingPriority {
    HIGH(0), NORMAL(1), LOW(2);
    private final int order;
    ContentIndexingPriority(int order) { this.order = order; }
    public int order() { return order; }
}
