package com.myhomelibcorp.application.content.search;

import com.myhomelibcorp.application.content.index.ContentIndexHealth;

/** Stable application-level signal that full-text search cannot safely use the current index. */
public final class ContentIndexUnavailableException extends RuntimeException {
    private final ContentIndexHealth.State state;

    public ContentIndexUnavailableException(ContentIndexHealth.State state) {
        super("Content index is not ready: " + (state == null ? ContentIndexHealth.State.ERROR : state));
        this.state = state == null ? ContentIndexHealth.State.ERROR : state;
    }

    public ContentIndexHealth.State state() {
        return state;
    }
}
