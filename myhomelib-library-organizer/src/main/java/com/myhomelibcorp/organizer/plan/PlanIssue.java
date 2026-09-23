package com.myhomelibcorp.organizer.plan;
public record PlanIssue(String code, String message, boolean blocking) {
    public PlanIssue { code = code == null ? "" : code; message = message == null ? "" : message; }
}
