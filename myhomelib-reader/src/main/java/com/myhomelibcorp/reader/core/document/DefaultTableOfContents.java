package com.myhomelibcorp.reader.core.document;

import com.myhomelibcorp.reader.api.TableOfContents;
import com.myhomelibcorp.reader.api.TocEntry;

import java.util.ArrayList;
import java.util.List;

public class DefaultTableOfContents implements TableOfContents {

    private final List<TocEntry> entries;

    public DefaultTableOfContents() {
        this.entries = new ArrayList<>();
    }

    public DefaultTableOfContents(List<TocEntry> entries) {
        this.entries = entries != null ? new ArrayList<>(entries) : new ArrayList<>();
    }

    @Override
    public List<TocEntry> entries() {
        return entries;
    }

    public void addEntry(TocEntry entry) {
        if (entry != null) {
            entries.add(entry);
        }
    }

    public void addEntry(String title, long textOffset, int level) {
        entries.add(new TocEntry(title, textOffset, level));
    }

    public void addAll(List<TocEntry> newEntries) {
        if (newEntries != null) {
            entries.addAll(newEntries);
        }
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    @Override
    public String toString() {
        return "DefaultTableOfContents{" +
                "entries=" + entries.size() +
                '}';
    }
}