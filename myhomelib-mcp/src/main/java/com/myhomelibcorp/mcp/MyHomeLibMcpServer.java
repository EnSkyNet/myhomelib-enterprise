package com.myhomelibcorp.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/**
 * Standalone, read-only Model Context Protocol server for MyHomeLib.
 * No Spring/JavaFX process is required. All SQLite connections are query-only.
 */
public final class MyHomeLibMcpServer implements AutoCloseable {
    private final ObjectMapper json = new ObjectMapper();
    private final LibraryDb db;
    private final BookContentReader content = new BookContentReader();
    private final InputStream input;
    private final BufferedWriter output;

    private MyHomeLibMcpServer(Path dbPath) throws Exception {
        this(dbPath, System.in, System.out);
    }

    /** Package-private transport injection keeps protocol tests deterministic. */
    MyHomeLibMcpServer(Path dbPath, InputStream input, OutputStream output) throws Exception {
        this.db = new LibraryDb(dbPath, json);
        this.input = input instanceof BufferedInputStream ? input : new BufferedInputStream(input);
        this.output = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
    }

    public static void main(String[] args) {
        try {
            Path db = resolveDb(args);
            try (MyHomeLibMcpServer server = new MyHomeLibMcpServer(db)) { server.loop(); }
        } catch (Exception e) {
            System.err.println("MyHomeLib MCP: " + e.getMessage());
            if (Boolean.getBoolean("myhomelib.mcp.debug")) e.printStackTrace(System.err);
            System.exit(2);
        }
    }

    void loop() throws Exception {
        String line;
        while ((line = readMessage()) != null) {
            if (line.isBlank()) continue;
            JsonNode req;
            try { req = json.readTree(line); } catch (Exception bad) { sendError(null, -32700, "Parse error"); continue; }
            JsonNode id = req.get("id");
            if (!req.isObject() || !req.hasNonNull("method") || !req.path("method").isTextual()) {
                if (id != null && !id.isNull()) sendError(id, -32600, "Invalid Request");
                continue;
            }
            String method = req.path("method").asText();
            if (method.startsWith("notifications/")) continue;
            try {
                JsonNode result = dispatch(method, req.path("params"));
                if (id != null && !id.isNull()) sendResult(id, result);
            } catch (MethodNotFoundException e) {
                if (id != null && !id.isNull()) sendError(id, -32601, e.getMessage());
            } catch (IllegalArgumentException e) {
                if (id != null && !id.isNull()) sendError(id, -32602, e.getMessage());
            } catch (Exception e) {
                if (id != null && !id.isNull()) sendError(id, -32603, e.getMessage());
            }
        }
    }

    /** Supports newline-delimited stdio and byte-accurate Content-Length framing. */
    private String readMessage() throws IOException {
        byte[] firstLine = readLineBytes();
        if (firstLine == null) return null;
        String first = new String(firstLine, StandardCharsets.UTF_8);
        if (!first.regionMatches(true, 0, "Content-Length:", 0, 15)) return first;

        int separator = first.indexOf(':');
        if (separator < 0) throw new IOException("Malformed Content-Length header");
        int len;
        try {
            len = Integer.parseInt(first.substring(separator + 1).trim());
        } catch (NumberFormatException e) {
            throw new IOException("Invalid Content-Length", e);
        }
        if (len < 0 || len > 16 * 1024 * 1024) throw new IOException("Content-Length out of range: " + len);

        byte[] header;
        do {
            header = readLineBytes();
            if (header == null) throw new EOFException("EOF inside MCP headers");
        } while (header.length != 0);

        byte[] payload = input.readNBytes(len);
        if (payload.length != len) throw new EOFException("EOF inside MCP payload");
        return new String(payload, StandardCharsets.UTF_8);
    }

    private byte[] readLineBytes() throws IOException {
        ByteArrayOutputStream line = new ByteArrayOutputStream(256);
        while (true) {
            int b = input.read();
            if (b < 0) return line.size() == 0 ? null : line.toByteArray();
            if (b == '\n') break;
            if (b != '\r') line.write(b);
            if (line.size() > 64 * 1024) throw new IOException("MCP header/line is too long");
        }
        return line.toByteArray();
    }

    private JsonNode dispatch(String method, JsonNode params) throws Exception {
        return switch (method) {
            case "initialize" -> initialize();
            case "ping" -> json.createObjectNode();
            case "tools/list" -> toolList();
            case "tools/call" -> callTool(params);
            default -> throw new MethodNotFoundException("Unknown method: " + method);
        };
    }

    private ObjectNode initialize() {
        ObjectNode r=json.createObjectNode();
        r.put("protocolVersion","2025-06-18");
        r.set("capabilities",json.createObjectNode().set("tools",json.createObjectNode().put("listChanged",false)));
        r.set("serverInfo",json.createObjectNode().put("name","MyHomeLib MCP").put("version","7.1.0"));
        return r;
    }

    private ObjectNode toolList() {
        ArrayNode tools=json.createArrayNode();
        tools.add(tool("search_books","Search books by title, author, series, genre, annotation, file or publisher",
                schema(Map.of("query","string","limit","integer","offset","integer"), List.of())));
        tools.add(tool("list_authors","List/search authors",schema(Map.of("query","string","limit","integer"),List.of())));
        tools.add(tool("list_series","List/search book series",schema(Map.of("query","string","limit","integer"),List.of())));
        tools.add(tool("list_genres","List/search genres",schema(Map.of("query","string","limit","integer"),List.of())));
        tools.add(tool("book_info","Return catalog metadata for a book id or LibID",schema(Map.of("book_id","string"),List.of("book_id"))));
        tools.add(tool("book_toc","Return FB2 or EPUB table of contents",schema(Map.of("book_id","string"),List.of("book_id"))));
        tools.add(tool("book_text","Read a bounded slice of book text",schema(Map.of("book_id","string","offset","integer","length","integer"),List.of("book_id"))));
        tools.add(tool("search_inside_book","Case-insensitive search inside book text",schema(Map.of("book_id","string","query","string","limit","integer"),List.of("book_id","query"))));
        return json.createObjectNode().set("tools",tools);
    }

    private JsonNode callTool(JsonNode params) throws Exception {
        String name=params.path("name").asText(); JsonNode a=params.path("arguments");
        JsonNode value=switch(name){
            case "search_books" -> db.searchBooks(a.path("query").asText(""), intArg(a,"limit",50,1,500), Math.max(0,a.path("offset").asInt(0)));
            case "list_authors" -> db.listAuthors(a.path("query").asText(""),intArg(a,"limit",100,1,500));
            case "list_series" -> db.listSeries(a.path("query").asText(""),intArg(a,"limit",100,1,500));
            case "list_genres" -> db.listGenres(a.path("query").asText(""),intArg(a,"limit",100,1,500));
            case "book_info" -> {ObjectNode b=db.bookById(required(a,"book_id")); if(b==null)throw new IllegalArgumentException("Book not found"); yield b;}
            case "book_toc" -> toc(required(a,"book_id"));
            case "book_text" -> bookText(a);
            case "search_inside_book" -> searchInside(a);
            default -> throw new IllegalArgumentException("Unknown tool: "+name);
        };
        String serialized=json.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        ObjectNode r=json.createObjectNode(); ArrayNode c=json.createArrayNode(); c.add(json.createObjectNode().put("type","text").put("text",serialized)); r.set("content",c); r.put("isError",false); return r;
    }

    private ArrayNode toc(String bookId)throws Exception{
        LibraryDb.BookLocation l=db.locate(bookId);if(l==null)throw new IllegalArgumentException("Book not found");ArrayNode a=json.createArrayNode();for(BookContentReader.TocItem x:content.toc(l))a.add(json.createObjectNode().put("level",x.level()).put("title",x.title()).put("ordinal",x.ordinal()));return a;
    }
    private ObjectNode bookText(JsonNode a)throws Exception{
        LibraryDb.BookLocation l=db.locate(required(a,"book_id"));if(l==null)throw new IllegalArgumentException("Book not found");String t=content.text(l);int off=Math.min(Math.max(0,a.path("offset").asInt(0)),t.length());int len=intArg(a,"length",12000,1,100000);int end=Math.min(t.length(),off+len);return json.createObjectNode().put("offset",off).put("nextOffset",end).put("totalLength",t.length()).put("text",t.substring(off,end));
    }
    private ObjectNode searchInside(JsonNode a)throws Exception{
        String q=required(a,"query");LibraryDb.BookLocation l=db.locate(required(a,"book_id"));if(l==null)throw new IllegalArgumentException("Book not found");String t=content.text(l),low=t.toLowerCase(Locale.ROOT),needle=q.toLowerCase(Locale.ROOT);int lim=intArg(a,"limit",20,1,100),from=0;ArrayNode hits=json.createArrayNode();while(hits.size()<lim){int at=low.indexOf(needle,from);if(at<0)break;int s=Math.max(0,at-120),e=Math.min(t.length(),at+q.length()+180);hits.add(json.createObjectNode().put("offset",at).put("snippet",t.substring(s,e).replace('\n',' ')));from=at+Math.max(1,needle.length());}ObjectNode result=json.createObjectNode().put("query",q).put("matches",hits.size()); result.set("hits",hits); return result;
    }

    private ObjectNode tool(String name,String description,ObjectNode input){return json.createObjectNode().put("name",name).put("description",description).set("inputSchema",input);}
    private ObjectNode schema(Map<String,String> props,List<String> required){ObjectNode s=json.createObjectNode().put("type","object");ObjectNode p=json.createObjectNode();props.forEach((k,v)->p.set(k,json.createObjectNode().put("type",v)));s.set("properties",p);ArrayNode r=json.createArrayNode();required.forEach(r::add);s.set("required",r);s.put("additionalProperties",false);return s;}
    private static String required(JsonNode a,String k){String v=a.path(k).asText("").trim();if(v.isEmpty())throw new IllegalArgumentException(k+" is required");return v;}
    private static int intArg(JsonNode a,String k,int d,int lo,int hi){int v=a.path(k).asInt(d);return Math.max(lo,Math.min(hi,v));}

    private synchronized void sendResult(JsonNode id,JsonNode result)throws IOException{ObjectNode r=json.createObjectNode().put("jsonrpc","2.0");r.set("id",id);r.set("result",result);write(r);}
    private synchronized void sendError(JsonNode id,int code,String message)throws IOException{ObjectNode r=json.createObjectNode().put("jsonrpc","2.0");if(id==null)r.putNull("id");else r.set("id",id);r.set("error",json.createObjectNode().put("code",code).put("message",message==null?"Error":message));write(r);}
    private void write(JsonNode n)throws IOException{output.write(json.writeValueAsString(n));output.write('\n');output.flush();}
    @Override public void close()throws Exception{db.close();}


    private static final class MethodNotFoundException extends IllegalArgumentException {
        MethodNotFoundException(String message) { super(message); }
    }

    private static Path resolveDb(String[] args)throws Exception{
        Map<String,String> a=new HashMap<>();for(int i=0;i<args.length;i++){if(args[i].startsWith("--")){String k=args[i].substring(2);String v=(i+1<args.length&&!args[i+1].startsWith("--"))?args[++i]:"true";a.put(k,v);}}
        String direct=first(a.get("db"),System.getenv("MYHOMELIB_DB"));if(direct!=null)return Path.of(direct);
        Path launch=Path.of(System.getProperty("user.dir",".")).toAbsolutePath();boolean portable=Files.isRegularFile(launch.resolve("myhomelib2.ini"));Path data=a.containsKey("data-dir")?Path.of(a.get("data-dir")):(portable?launch.resolve("data"):Path.of(System.getProperty("user.home"),".myhomelibcorp"));
        Path meta=a.containsKey("meta")?Path.of(a.get("meta")):data.resolve("meta.db");if(!Files.isRegularFile(meta))throw new IllegalArgumentException("Use --db <collection.db>; metadata DB not found: "+meta);
        List<Candidate> cs=new ArrayList<>();try(Connection c=DriverManager.getConnection("jdbc:sqlite:"+meta.toAbsolutePath());PreparedStatement ps=c.prepareStatement("SELECT id,name,db_file FROM collections ORDER BY name");ResultSet rs=ps.executeQuery()){while(rs.next())cs.add(new Candidate(rs.getString(1),rs.getString(2),rs.getString(3)));}
        String wanted=a.get("collection");if(wanted!=null)for(Candidate x:cs)if(wanted.equalsIgnoreCase(x.id)||wanted.equalsIgnoreCase(x.name))return Path.of(x.db);
        if(cs.size()==1)return Path.of(cs.get(0).db);throw new IllegalArgumentException("Multiple collections found; use --collection <name|id> or --db <file>. Available: "+cs.stream().map(x->x.name).toList());
    }
    private static String first(String...v){for(String x:v)if(x!=null&&!x.isBlank())return x;return null;}
    private record Candidate(String id,String name,String db){}
}
