package com.myhomelibcorp.startup;

import com.myhomelibcorp.domain.model.collection.Collection;

import java.util.List;

public record StartupReport(Collection activeCollection, List<StartupTaskOutcome> outcomes) {
    public StartupReport {
        outcomes = List.copyOf(outcomes);
    }

    public boolean degraded() {
        return outcomes.stream().anyMatch(outcome -> outcome.status() == StartupTaskOutcome.Status.DEGRADED);
    }
}
