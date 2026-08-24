package com.myhomelibcorp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LibraryDbWalReadOnlyTest {
    @TempDir Path temp;

    @Test
    void seesCommittedWalChangesWhileDesktopWriterRemainsOpen() throws Exception {
        Path dbPath = temp.resolve("wal-collection.db");
        try (Connection writer = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath())) {
            try (var s = writer.createStatement()) {
                s.execute("PRAGMA journal_mode=WAL");
                s.execute("PRAGMA wal_autocheckpoint=0");
                s.execute("CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT,series TEXT,sequence_number INTEGER,language TEXT,year INTEGER,publisher TEXT,lib_id TEXT,library_rate INTEGER DEFAULT 0,rate INTEGER DEFAULT 0,progress INTEGER DEFAULT 0,file_name TEXT,folder TEXT,archive_entry TEXT,collection_root TEXT,local INTEGER DEFAULT 1,deleted INTEGER DEFAULT 0,annotation TEXT DEFAULT '',keywords TEXT DEFAULT '')");
                s.execute("CREATE TABLE authors(id INTEGER PRIMARY KEY,last_name TEXT DEFAULT '',first_name TEXT DEFAULT '',middle_name TEXT DEFAULT '',annotation TEXT DEFAULT '')");
                s.execute("CREATE TABLE book_authors(book_id TEXT,author_id INTEGER)");
                s.execute("CREATE TABLE genres(code TEXT PRIMARY KEY,name TEXT)");
                s.execute("CREATE TABLE book_genres(book_id TEXT,genre_code TEXT)");
                s.execute("INSERT INTO books(id,title,file_name,collection_root) VALUES('wal-1','WAL visible','book.txt','" + temp.toString().replace("'", "''") + "')");
            }

            assertTrue(java.nio.file.Files.exists(Path.of(dbPath + "-wal")));
            try (LibraryDb reader = new LibraryDb(dbPath, new ObjectMapper())) {
                var rows = reader.searchBooks("WAL visible", 10, 0);
                assertEquals(1, rows.size());
                assertEquals("wal-1", rows.get(0).path("id").asText());
            }
        }
    }
}
