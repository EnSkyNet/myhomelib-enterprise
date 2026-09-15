package com.myhomelibcorp.application.port.out.activity;

import com.myhomelibcorp.application.activity.BookActivitySummary;

import java.util.Collection;
import java.util.Map;

public interface BookActivityQueryPort {
    Map<String, BookActivitySummary> summarize(Collection<String> bookIds);
}
