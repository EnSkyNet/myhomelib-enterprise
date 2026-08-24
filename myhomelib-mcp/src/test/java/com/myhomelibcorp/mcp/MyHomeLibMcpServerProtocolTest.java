package com.myhomelibcorp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MyHomeLibMcpServerProtocolTest {
    @TempDir Path temp;
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void handlesInitializeNotificationsToolsParseErrorsAndMethodNotFound() throws Exception {
        Path db = createDb();
        String input = String.join("\n",
                "{bad json",
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}",
                "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"search_books\",\"arguments\":{\"query\":\"Книга\"}}}",
                "{\"jsonrpc\":\"2.0\",\"id\":4,\"method\":\"does/not/exist\",\"params\":{}}",
                "{\"jsonrpc\":\"2.0\",\"id\":5,\"method\":\"tools/call\",\"params\":{\"name\":\"book_info\",\"arguments\":{}}}",
                "");

        List<JsonNode> out = run(db, input.getBytes(StandardCharsets.UTF_8));
        assertEquals(6, out.size()); // notification produces no response
        assertEquals(-32700, out.get(0).path("error").path("code").asInt());
        assertEquals("2025-06-18", out.get(1).path("result").path("protocolVersion").asText());
        assertTrue(out.get(2).path("result").path("tools").isArray());
        assertTrue(out.get(2).path("result").path("tools").toString().contains("book_text"));
        assertFalse(out.get(3).path("result").path("content").isEmpty());
        assertEquals(-32601, out.get(4).path("error").path("code").asInt());
        assertEquals(-32602, out.get(5).path("error").path("code").asInt());
    }

    @Test
    void contentLengthCountsUtf8BytesNotJavaCharacters() throws Exception {
        Path db = createDb();
        String request = "{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{\"name\":\"search_books\",\"arguments\":{\"query\":\"Українська книга\"}}}";
        byte[] payload = request.getBytes(StandardCharsets.UTF_8);
        byte[] header = ("Content-Length: " + payload.length + "\r\nContent-Type: application/json\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII);
        byte[] framed = new byte[header.length + payload.length];
        System.arraycopy(header, 0, framed, 0, header.length);
        System.arraycopy(payload, 0, framed, header.length, payload.length);

        List<JsonNode> out = run(db, framed);
        assertEquals(1, out.size());
        assertEquals(7, out.get(0).path("id").asInt());
        assertTrue(out.get(0).has("result"));
    }

    @Test
    void cleanClientDisconnectEndsLoopWithoutOutput() throws Exception {
        Path db = createDb();
        assertTrue(run(db, new byte[0]).isEmpty());
    }

    private List<JsonNode> run(Path db, byte[] input) throws Exception {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (MyHomeLibMcpServer server = new MyHomeLibMcpServer(db, new ByteArrayInputStream(input), output)) {
            server.loop();
        }
        String text = output.toString(StandardCharsets.UTF_8);
        if (text.isBlank()) return List.of();
        return Arrays.stream(text.split("\\R"))
                .filter(s -> !s.isBlank())
                .map(s -> {
                    try { return json.readTree(s); }
                    catch (Exception e) { throw new RuntimeException(e); }
                })
                .toList();
    }

    private Path createDb() throws Exception {
        Path db = temp.resolve("collection.db");
        try (var c = DriverManager.getConnection("jdbc:sqlite:" + db.toAbsolutePath());
             var s = c.createStatement()) {
            s.execute("CREATE TABLE books(id TEXT PRIMARY KEY,title TEXT,series TEXT,sequence_number INTEGER,language TEXT,year INTEGER,publisher TEXT,lib_id TEXT,library_rate INTEGER DEFAULT 0,rate INTEGER DEFAULT 0,progress INTEGER DEFAULT 0,file_name TEXT,folder TEXT,archive_entry TEXT,collection_root TEXT,local INTEGER DEFAULT 1,deleted INTEGER DEFAULT 0,annotation TEXT DEFAULT '',keywords TEXT DEFAULT '')");
            s.execute("CREATE TABLE authors(id INTEGER PRIMARY KEY,last_name TEXT DEFAULT '',first_name TEXT DEFAULT '',middle_name TEXT DEFAULT '',annotation TEXT DEFAULT '')");
            s.execute("CREATE TABLE book_authors(book_id TEXT,author_id INTEGER)");
            s.execute("CREATE TABLE genres(code TEXT PRIMARY KEY,name TEXT)");
            s.execute("CREATE TABLE book_genres(book_id TEXT,genre_code TEXT)");
            s.execute("INSERT INTO books(id,title,file_name,collection_root) VALUES('b1','Українська книга','book.txt','" + temp.toString().replace("'", "''") + "')");
        }
        return db;
    }
}
