package com.myhomelibcorp.infrastructure.download.scenario;

public record DownloadScenarioCommand(Type type, String first, String second, int line) {
    public enum Type { CHECK, REDIR, PAUSE, GET, POST, ADD }
}
