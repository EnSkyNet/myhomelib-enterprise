package com.myhomelibcorp.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;

/** Read-only access to a MyHomeLib collection database. */
final class LibraryDb implements AutoCloseable {
    private final Connection connection;
    private final ObjectMapper json;

    LibraryDb(Path db, ObjectMapper json) throws Exception {
        Path p = db.toAbsolutePath().normalize();
        if (!Files.isRegularFile(p)) throw new IllegalArgumentException("Collection DB not found: " + p);
        this.json = json;
        String uri = p.toUri().toASCIIString();
        this.connection = DriverManager.getConnection("jdbc:sqlite:" + uri + "?mode=ro");
        try (Statement s = connection.createStatement()) {
            s.execute("PRAGMA query_only=ON");
            s.execute("PRAGMA busy_timeout=5000");
        }
    }

    ArrayNode searchBooks(String q, int limit, int offset) throws SQLException {
        String term = q == null ? "" : q.trim();
        String like = "%" + escapeLike(term) + "%";
        String sql = """
                SELECT b.id,b.title,b.series,b.sequence_number,b.language,b.year,b.publisher,b.lib_id,
                       b.library_rate,b.rate,b.progress,b.file_name,b.folder,b.archive_entry,b.collection_root,b.local,
                       COALESCE(GROUP_CONCAT(DISTINCT TRIM(a.last_name || ' ' || a.first_name || ' ' || a.middle_name)), '') authors,
                       COALESCE(GROUP_CONCAT(DISTINCT g.name), '') genres
                FROM books b
                LEFT JOIN book_authors ba ON ba.book_id=b.id LEFT JOIN authors a ON a.id=ba.author_id
                LEFT JOIN book_genres bg ON bg.book_id=b.id LEFT JOIN genres g ON g.code=bg.genre_code
                WHERE b.deleted=0 AND (?='' OR b.title LIKE ? ESCAPE '\\' OR b.series LIKE ? ESCAPE '\\'
                      OR b.annotation LIKE ? ESCAPE '\\' OR b.keywords LIKE ? ESCAPE '\\'
                      OR b.file_name LIKE ? ESCAPE '\\' OR b.publisher LIKE ? ESCAPE '\\'
                      OR a.last_name LIKE ? ESCAPE '\\' OR a.first_name LIKE ? ESCAPE '\\' OR g.name LIKE ? ESCAPE '\\')
                GROUP BY b.id ORDER BY b.title COLLATE NOCASE LIMIT ? OFFSET ?
                """;
        try (PreparedStatement ps = connection.prepareStatement(sql)) {
            ps.setString(1, term);
            for (int i=2;i<=10;i++) ps.setString(i, like);
            ps.setInt(11, clamp(limit,1,500)); ps.setInt(12, Math.max(0,offset));
            return rows(ps, this::bookNode);
        }
    }

    ArrayNode listAuthors(String q, int limit) throws SQLException {
        return simpleList("""
            SELECT a.id,TRIM(a.last_name || ' ' || a.first_name || ' ' || a.middle_name) name,a.annotation,
                   COUNT(DISTINCT ba.book_id) books FROM authors a LEFT JOIN book_authors ba ON ba.author_id=a.id
            WHERE (?='' OR a.last_name LIKE ? ESCAPE '\\' OR a.first_name LIKE ? ESCAPE '\\')
            GROUP BY a.id ORDER BY a.last_name COLLATE NOCASE,a.first_name COLLATE NOCASE LIMIT ?
            """, q, limit, 3);
    }

    ArrayNode listSeries(String q, int limit) throws SQLException {
        String term=nvl(q).trim(), like="%"+escapeLike(term)+"%";
        try (PreparedStatement ps=connection.prepareStatement("""
            SELECT series name,COUNT(*) books FROM books WHERE deleted=0 AND series<>'' AND (?='' OR series LIKE ? ESCAPE '\\')
            GROUP BY series ORDER BY series COLLATE NOCASE LIMIT ?
            """)) {
            ps.setString(1,term);ps.setString(2,like);ps.setInt(3,clamp(limit,1,500)); return rowsGeneric(ps);
        }
    }

    ArrayNode listGenres(String q, int limit) throws SQLException {
        return simpleList("""
            SELECT g.code AS id,g.name,COUNT(DISTINCT bg.book_id) books FROM genres g LEFT JOIN book_genres bg ON bg.genre_code=g.code
            WHERE (?='' OR g.name LIKE ? ESCAPE '\\' OR g.code LIKE ? ESCAPE '\\')
            GROUP BY g.code ORDER BY g.name COLLATE NOCASE LIMIT ?
            """, q, limit, 3);
    }

    ObjectNode bookById(String id) throws SQLException {
        String sql="""
            SELECT b.*,COALESCE(GROUP_CONCAT(DISTINCT TRIM(a.last_name || ' ' || a.first_name || ' ' || a.middle_name)), '') authors,
                   COALESCE(GROUP_CONCAT(DISTINCT g.name), '') genres
            FROM books b LEFT JOIN book_authors ba ON ba.book_id=b.id LEFT JOIN authors a ON a.id=ba.author_id
            LEFT JOIN book_genres bg ON bg.book_id=b.id LEFT JOIN genres g ON g.code=bg.genre_code
            WHERE b.id=? OR b.lib_id=? GROUP BY b.id LIMIT 1
            """;
        try (PreparedStatement ps=connection.prepareStatement(sql)) {
            ps.setString(1,id);ps.setString(2,id);
            try(ResultSet rs=ps.executeQuery()){return rs.next()?bookNode(rs):null;}
        }
    }

    BookLocation locate(String id) throws SQLException {
        try (PreparedStatement ps=connection.prepareStatement("""
            SELECT id,file_name,folder,archive_entry,collection_root FROM books
            WHERE (id=? OR lib_id=?) AND deleted=0 LIMIT 1
            """)) {
            ps.setString(1,id); ps.setString(2,id);
            try(ResultSet rs=ps.executeQuery()){
                if(!rs.next()) return null;
                return new BookLocation(rs.getString("id"),nvl(rs.getString("file_name")),nvl(rs.getString("folder")),
                        nvl(rs.getString("archive_entry")),nvl(rs.getString("collection_root")));
            }
        }
    }

    private ArrayNode simpleList(String sql,String q,int limit,int likeCount) throws SQLException {
        String term=nvl(q).trim(),like="%"+escapeLike(term)+"%";
        try(PreparedStatement ps=connection.prepareStatement(sql)){
            ps.setString(1,term); for(int i=0;i<likeCount-1;i++) ps.setString(2+i,like);
            ps.setInt(1+likeCount,clamp(limit,1,500)); return rowsGeneric(ps);
        }
    }

    private ArrayNode rowsGeneric(PreparedStatement ps)throws SQLException{
        ArrayNode a=json.createArrayNode(); try(ResultSet rs=ps.executeQuery()){
            ResultSetMetaData md=rs.getMetaData(); while(rs.next()){
                ObjectNode o=json.createObjectNode(); for(int i=1;i<=md.getColumnCount();i++){
                    Object v=rs.getObject(i); if(v==null)o.putNull(md.getColumnLabel(i)); else if(v instanceof Number n)o.put(md.getColumnLabel(i),n.doubleValue()); else o.put(md.getColumnLabel(i),v.toString());
                } a.add(o);
            }} return a;
    }
    private ArrayNode rows(PreparedStatement ps, RowMapper mapper)throws SQLException{ArrayNode a=json.createArrayNode();try(ResultSet rs=ps.executeQuery()){while(rs.next())a.add(mapper.map(rs));}return a;}
    private ObjectNode bookNode(ResultSet rs)throws SQLException{
        ObjectNode o=json.createObjectNode();
        put(o,"id",rs,"id");put(o,"libId",rs,"lib_id");put(o,"title",rs,"title");put(o,"authors",rs,"authors");put(o,"series",rs,"series");put(o,"genres",rs,"genres");
        put(o,"language",rs,"language"); putInt(o,"year",rs,"year"); put(o,"publisher",rs,"publisher"); putInt(o,"libraryRate",rs,"library_rate"); putInt(o,"userRate",rs,"rate"); putInt(o,"progress",rs,"progress");
        put(o,"fileName",rs,"file_name");put(o,"archiveEntry",rs,"archive_entry");putBool(o,"local",rs,"local"); return o;
    }
    private static void put(ObjectNode o,String k,ResultSet r,String c){try{String v=r.getString(c);if(v==null)o.putNull(k);else o.put(k,v);}catch(SQLException e){o.putNull(k);}}
    private static void putInt(ObjectNode o,String k,ResultSet r,String c){try{int v=r.getInt(c);if(r.wasNull())o.putNull(k);else o.put(k,v);}catch(SQLException e){o.putNull(k);}}
    private static void putBool(ObjectNode o,String k,ResultSet r,String c){try{o.put(k,r.getInt(c)!=0);}catch(SQLException e){o.put(k,false);}}
    private static String escapeLike(String s){return nvl(s).replace("\\","\\\\").replace("%","\\%").replace("_","\\_");}
    private static String nvl(String s){return s==null?"":s;}
    private static int clamp(int n,int lo,int hi){return Math.max(lo,Math.min(hi,n));}
    @Override public void close() throws SQLException {connection.close();}
    record BookLocation(String id,String fileName,String folder,String archiveEntry,String collectionRoot){}
    @FunctionalInterface private interface RowMapper{ObjectNode map(ResultSet rs)throws SQLException;}
}
