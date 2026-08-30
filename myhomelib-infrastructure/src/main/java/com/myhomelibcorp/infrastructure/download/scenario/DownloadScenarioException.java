package com.myhomelibcorp.infrastructure.download.scenario;

import java.io.IOException;

public class DownloadScenarioException extends IOException {
    public DownloadScenarioException(String message) { super(message); }
    public DownloadScenarioException(String message, Throwable cause) { super(message, cause); }
}
