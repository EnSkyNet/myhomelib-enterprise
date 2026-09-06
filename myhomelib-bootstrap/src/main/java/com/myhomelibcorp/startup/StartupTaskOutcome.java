package com.myhomelibcorp.startup;

public record StartupTaskOutcome(
        String taskId,
        StartupFailurePolicy policy,
        Status status,
        long durationMillis,
        String detail) {

    public StartupTaskOutcome {
        detail = detail == null ? "" : detail;
    }

    public enum Status {
        SUCCESS,
        SKIPPED,
        DEGRADED
    }
}
