package com.myhomelibcorp.startup;

import com.myhomelibcorp.domain.model.collection.Collection;

import java.util.Objects;

/** Mutable state shared only inside one synchronous startup orchestration run. */
public final class StartupContext {
    private Collection activeCollection;
    private boolean reusableSearchIndex;

    public StartupContext(Collection activeCollection) {
        this.activeCollection = Objects.requireNonNull(activeCollection, "activeCollection");
    }

    public Collection activeCollection() {
        return activeCollection;
    }

    public void activeCollection(Collection activeCollection) {
        this.activeCollection = Objects.requireNonNull(activeCollection, "activeCollection");
    }

    public boolean reusableSearchIndex() {
        return reusableSearchIndex;
    }

    public void reusableSearchIndex(boolean reusableSearchIndex) {
        this.reusableSearchIndex = reusableSearchIndex;
    }
}
