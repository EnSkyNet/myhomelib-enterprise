package com.myhomelibcorp.startup;

import com.myhomelibcorp.shared.util.ThrowableText;
public class StartupException extends RuntimeException {
    private final String taskId;

    public StartupException(String taskId, Throwable cause) {
        super("Required startup task failed: " + taskId + ": " + rootMessage(cause), cause);
        this.taskId = taskId;
    }

    public String taskId() {
        return taskId;
    }

    private static String rootMessage(Throwable error) {
        return ThrowableText.rootMessage(error, "Unknown error");
    }
}
