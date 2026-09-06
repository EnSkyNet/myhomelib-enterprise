package com.myhomelibcorp.startup;

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
        Throwable current = error;
        while (current.getCause() != null && current.getCause() != current) current = current.getCause();
        String message = current.getMessage();
        return message == null || message.isBlank() ? current.getClass().getSimpleName() : message;
    }
}
