package com.myhomelibcorp.application.content.indexing;

public record ContentIndexingOutcome(Status status, String message) {
    public enum Status { COMPLETED, CANCELLED, FAILED }
    public ContentIndexingOutcome { message = message == null ? "" : message; }
    public static ContentIndexingOutcome completed() { return new ContentIndexingOutcome(Status.COMPLETED, ""); }
    public static ContentIndexingOutcome cancelled() { return new ContentIndexingOutcome(Status.CANCELLED, ""); }
    public static ContentIndexingOutcome failed(String message) { return new ContentIndexingOutcome(Status.FAILED, message); }
}
