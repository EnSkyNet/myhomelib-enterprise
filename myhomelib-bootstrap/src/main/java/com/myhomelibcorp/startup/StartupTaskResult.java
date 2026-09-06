package com.myhomelibcorp.startup;

public record StartupTaskResult(boolean executed, String detail) {
    public StartupTaskResult {
        detail = detail == null ? "" : detail;
    }

    public static StartupTaskResult success(String detail) {
        return new StartupTaskResult(true, detail);
    }

    public static StartupTaskResult skipped(String detail) {
        return new StartupTaskResult(false, detail);
    }
}
