package com.myhomelibcorp.infrastructure.collection.legacy;

import com.myhomelibcorp.application.port.out.collection.LegacyCollectionAttachPort;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.AuthorSearchNameNormalizer;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Attaches a modern SQLite collection or losslessly migrates an original Delphi .hlc2 into the modern schema. */
@Component
@RequiredArgsConstructor
@Slf4j
public class SqliteLegacyCollectionAttachAdapter implements LegacyCollectionAttachPort {
    private final CollectionRepository collectionRepository;
    private final CollectionLifecycleService lifecycle;
    private final CollectionManager collectionManager;

    @Override
    public AttachResult attach(Path source, String collectionName, Path root) {
        try (Connection src = openReadOnly(source)) {
            Set<String> bookColumns = columns(src, "books");
            if (bookColumns.contains("id") && bookColumns.contains("file_name")) {
                Collection c = collectionRepository.save(new Collection(UUID.randomUUID().toString(), collectionName, root,
                        source.toString(), 0, null, null, null, "Attached SQLite collection"));
                lifecycle.initializeCollection(c, true);
                long count = scalar(collectionManager.getCurrentJdbcTemplate(), "SELECT COUNT(*) FROM books");
                long authors = scalar(collectionManager.getCurrentJdbcTemplate(), "SELECT COUNT(*) FROM authors");
                long genres = scalar(collectionManager.getCurrentJdbcTemplate(), "SELECT COUNT(*) FROM genres");
                return new AttachResult(c, count, authors, genres, false);
            }
            if (!bookColumns.contains("bookid") || !bookColumns.contains("title")) {
                throw new IllegalArgumentException("Файл не схожий на колекцію MyHomeLib (.hlc2)");
            }

            Path target = AppPaths.librariesDir().resolve(UUID.randomUUID() + ".db");
            Collection c = collectionRepository.save(new Collection(UUID.randomUUID().toString(), collectionName, root,
                    target.toString(), 0, null, null, null, "Migrated from " + source));
            lifecycle.initializeCollection(c, false); // creates the modern schema via Flyway
            JdbcTemplate jt = collectionManager.getCurrentJdbcTemplate();
            MigrationStats stats = migrate(src, jt, root);
            lifecycle.rebuildSearchIndex();
            return new AttachResult(c, stats.books, stats.authors, stats.genres, true);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalStateException("Не вдалося підключити .hlc2: " + e.getMessage(), e);
        }
    }

    private MigrationStats migrate(Connection src, JdbcTemplate jt, Path root) throws SQLException {
        Map<Long,String> series = readSeries(src);
        Map<Long,String> authorIds = migrateAuthors(src, jt);
        Map<String,String> genres = migrateGenres(src, jt);
        Map<Long,String> bookIds = new HashMap<>();
        long books = 0;

        try (Statement st = src.createStatement(); ResultSet rs = st.executeQuery("SELECT * FROM Books")) {
            Set<String> cols = columnNames(rs);
            List<Object[]> batch = new ArrayList<>(1000);
            while (rs.next()) {
                long oldId = longValue(rs, cols, "bookid", 0L);
                String libId = value(rs, cols, "libid");
                String id = stableId("book", !libId.isBlank() ? libId : Long.toString(oldId));
                bookIds.put(oldId, id);
                String title = blankTo(value(rs, cols, "title"), "Без назви");
                long seriesId = longValue(rs, cols, "seriesid", 0L);
                String folder = normalize(value(rs, cols, "folder"));
                String originalFile = normalize(value(rs, cols, "filename"));
                String ext = trimDot(value(rs, cols, "ext"));
                String fileName = originalFile;
                if (!ext.isBlank() && !fileName.toLowerCase(Locale.ROOT).endsWith("." + ext.toLowerCase(Locale.ROOT))) fileName += "." + ext;
                boolean archive = isArchive(folder);
                String archiveEntry = archive ? fileName : "";
                String storedFile = archive ? folder : fileName;
                String storedFolder = archive ? "" : folder;
                String date = blankTo(value(rs, cols, "updatedate"), LocalDateTime.now().toString());
                Integer year = intOrNull(rs, cols, "pubyear");
                batch.add(new Object[]{id,title,series.getOrDefault(seriesId,""),intValue(rs,cols,"seqnumber",0),storedFile,storedFolder,
                        archiveEntry,blankTo(value(rs,cols,"lang"),"uk"),longValue(rs,cols,"booksize",0L),value(rs,cols,"keywords"),
                        value(rs,cols,"annotation"),intValue(rs,cols,"rate",0),intValue(rs,cols,"progress",0),date,value(rs,cols,"isbn"),
                        intValue(rs,cols,"isdeleted",0),intValue(rs,cols,"islocal",0),value(rs,cols,"review"),date,root.toString(),year,
                        value(rs,cols,"publisher"),libId,intValue(rs,cols,"librate",0),value(rs,cols,"translators"),value(rs,cols,"city"),""});
                if (batch.size() >= 1000) { insertBooks(jt,batch); books += batch.size(); batch.clear(); }
            }
            if (!batch.isEmpty()) { insertBooks(jt,batch); books += batch.size(); }
        }

        migrateBookAuthors(src, jt, bookIds, authorIds);
        migrateBookGenres(src, jt, bookIds, genres);
        refreshDenormalizedBookFields(jt);
        return new MigrationStats(books, authorIds.size(), genres.size());
    }

    private Map<Long,String> readSeries(Connection src) {
        Map<Long,String> map = new HashMap<>();
        if (!tableExists(src,"Series")) return map;
        try (Statement st=src.createStatement(); ResultSet rs=st.executeQuery("SELECT * FROM Series")) {
            Set<String> c=columnNames(rs);
            while(rs.next()) map.put(longValue(rs,c,"seriesid",0), value(rs,c,"seriestitle"));
        } catch(Exception e){ log.warn("Cannot migrate Series",e); }
        return map;
    }

    private Map<Long,String> migrateAuthors(Connection src, JdbcTemplate jt) {
        Map<Long,String> map=new HashMap<>();
        if(!tableExists(src,"Authors")) return map;
        try(Statement st=src.createStatement(); ResultSet rs=st.executeQuery("SELECT * FROM Authors")){
            Set<String> c=columnNames(rs); List<Object[]> batch=new ArrayList<>();
            while(rs.next()){
                long old=longValue(rs,c,"authorid",0); String id=stableId("author",Long.toString(old)); map.put(old,id);
                String first=value(rs,c,"firstname"), middle=value(rs,c,"middlename"), last=value(rs,c,"lastname");
                String search=AuthorSearchNameNormalizer.normalize(first, middle, last);
                batch.add(new Object[]{id,first,middle,last,search,value(rs,c,"annotation")});
            }
            jt.batchUpdate("""
                    INSERT INTO authors(id,first_name,middle_name,last_name,search_name,annotation) VALUES (?,?,?,?,?,?)
                    ON CONFLICT(id) DO UPDATE SET first_name=excluded.first_name,middle_name=excluded.middle_name,
                    last_name=excluded.last_name,search_name=excluded.search_name,annotation=excluded.annotation
                    """,batch);
        }catch(Exception e){ throw new IllegalStateException("Помилка перенесення авторів",e); }
        return map;
    }

    private Map<String,String> migrateGenres(Connection src, JdbcTemplate jt) {
        Map<String,String> result=new HashMap<>();
        if(!tableExists(src,"Genres")) return result;
        try(Statement st=src.createStatement(); ResultSet rs=st.executeQuery("SELECT * FROM Genres")){
            Set<String> c=columnNames(rs); List<Object[]> batch=new ArrayList<>();
            while(rs.next()){
                String code=firstNonBlank(value(rs,c,"genrecode"),value(rs,c,"fb2code")); if(code.isBlank()) continue;
                result.put(code,code); batch.add(new Object[]{code,blankTo(value(rs,c,"genrealias"),code),value(rs,c,"parentcode"),value(rs,c,"fb2code")});
            }
            jt.batchUpdate("INSERT OR REPLACE INTO genres(code,name,parent_code,fb2_code) VALUES (?,?,?,?)",batch);
        }catch(Exception e){ log.warn("Cannot migrate genres",e); }
        return result;
    }

    private void migrateBookAuthors(Connection src, JdbcTemplate jt, Map<Long,String> books, Map<Long,String> authors) {
        if(!tableExists(src,"Author_List")) return;
        try(Statement st=src.createStatement(); ResultSet rs=st.executeQuery("SELECT * FROM Author_List")){
            Set<String> c=columnNames(rs); List<Object[]> batch=new ArrayList<>();
            while(rs.next()){
                String b=books.get(longValue(rs,c,"bookid",-1)), a=authors.get(longValue(rs,c,"authorid",-1));
                if(b!=null&&a!=null) batch.add(new Object[]{b,a});
            }
            jt.batchUpdate("INSERT OR IGNORE INTO book_authors(book_id,author_id) VALUES (?,?)",batch);
        }catch(Exception e){ log.warn("Cannot migrate author links",e); }
    }

    private void migrateBookGenres(Connection src, JdbcTemplate jt, Map<Long,String> books, Map<String,String> genres) {
        if(!tableExists(src,"Genre_List")) return;
        try(Statement st=src.createStatement(); ResultSet rs=st.executeQuery("SELECT * FROM Genre_List")){
            Set<String> c=columnNames(rs); List<Object[]> batch=new ArrayList<>();
            while(rs.next()){
                String b=books.get(longValue(rs,c,"bookid",-1)), g=value(rs,c,"genrecode");
                if(b!=null&&!g.isBlank()){ if(!genres.containsKey(g)) jt.update("INSERT OR IGNORE INTO genres(code,name) VALUES (?,?)",g,g); batch.add(new Object[]{b,g}); }
            }
            jt.batchUpdate("INSERT OR IGNORE INTO book_genres(book_id,genre_code) VALUES (?,?)",batch);
        }catch(Exception e){ log.warn("Cannot migrate genre links",e); }
    }

    /** Stage 8/9 compatibility for HLC2 imported after V33 has already run. */
    private void refreshDenormalizedBookFields(JdbcTemplate jt) {
        jt.update("""
                UPDATE books
                SET format = CASE
                    WHEN LOWER(COALESCE(NULLIF(archive_entry,''), file_name, '')) LIKE '%.fb2.zip' THEN 'FB2ZIP'
                    WHEN LOWER(COALESCE(NULLIF(archive_entry,''), file_name, '')) LIKE '%.fb2' THEN 'FB2'
                    WHEN LOWER(COALESCE(NULLIF(archive_entry,''), file_name, '')) LIKE '%.epub' THEN 'EPUB'
                    WHEN LOWER(COALESCE(NULLIF(archive_entry,''), file_name, '')) LIKE '%.pdf' THEN 'PDF'
                    WHEN LOWER(COALESCE(NULLIF(archive_entry,''), file_name, '')) LIKE '%.mobi' THEN 'MOBI'
                    WHEN LOWER(COALESCE(NULLIF(archive_entry,''), file_name, '')) LIKE '%.inpx' THEN 'INPX'
                    WHEN LOWER(COALESCE(NULLIF(archive_entry,''), file_name, '')) LIKE '%.zip' THEN 'ZIP'
                    ELSE 'UNKNOWN'
                END,
                author_sort = COALESCE((
                    SELECT MIN(LOWER(TRIM(COALESCE(a.last_name,'') || ' ' || COALESCE(a.first_name,'') || ' ' || COALESCE(a.middle_name,''))))
                    FROM book_authors ba JOIN authors a ON a.id=ba.author_id
                    WHERE ba.book_id=books.id
                ), '')
                """);
    }

    private void insertBooks(JdbcTemplate jt,List<Object[]> batch){
        jt.batchUpdate("""
                INSERT INTO books(id,title,series,sequence_number,file_name,folder,archive_entry,language,file_size,keywords,annotation,
                rate,progress,update_date,isbn,deleted,local,review,created_at,collection_root,year,publisher,lib_id,library_rate,translators,city,source_url)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT(id) DO UPDATE SET title=excluded.title,series=excluded.series,sequence_number=excluded.sequence_number,
                file_name=excluded.file_name,folder=excluded.folder,archive_entry=excluded.archive_entry,language=excluded.language,
                file_size=excluded.file_size,keywords=excluded.keywords,annotation=excluded.annotation,update_date=excluded.update_date,
                isbn=excluded.isbn,deleted=excluded.deleted,local=excluded.local,collection_root=excluded.collection_root,year=excluded.year,
                publisher=excluded.publisher,lib_id=excluded.lib_id,library_rate=excluded.library_rate,translators=excluded.translators,city=excluded.city
                """,batch);
    }

    private static Connection openReadOnly(Path p) throws SQLException { Connection c=DriverManager.getConnection("jdbc:sqlite:"+p); c.setReadOnly(true); return c; }
    private static Set<String> columns(Connection c,String table) throws SQLException { Set<String>s=new HashSet<>(); try(Statement st=c.createStatement();ResultSet r=st.executeQuery("PRAGMA table_info('"+table.replace("'","''")+"')")){while(r.next())s.add(r.getString("name").toLowerCase(Locale.ROOT));} return s; }
    private static boolean tableExists(Connection c,String table){try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND lower(name)=lower(?)")){p.setString(1,table);try(ResultSet r=p.executeQuery()){return r.next();}}catch(Exception e){return false;}}
    private static Set<String> columnNames(ResultSet r)throws SQLException{Set<String>s=new HashSet<>();ResultSetMetaData m=r.getMetaData();for(int i=1;i<=m.getColumnCount();i++)s.add(m.getColumnLabel(i).toLowerCase(Locale.ROOT));return s;}
    private static String value(ResultSet r,Set<String> c,String name){if(!c.contains(name.toLowerCase(Locale.ROOT)))return "";try{Object o=r.getObject(name);return o==null?"":String.valueOf(o).trim();}catch(Exception e){try{Object o=r.getObject(findColumn(r,name));return o==null?"":String.valueOf(o).trim();}catch(Exception ignored){return "";}}}
    private static int findColumn(ResultSet r,String name)throws SQLException{ResultSetMetaData m=r.getMetaData();for(int i=1;i<=m.getColumnCount();i++)if(m.getColumnLabel(i).equalsIgnoreCase(name))return i;throw new SQLException(name);}
    private static long longValue(ResultSet r,Set<String>c,String n,long d){try{String v=value(r,c,n);return v.isBlank()?d:Long.parseLong(v);}catch(Exception e){return d;}}
    private static int intValue(ResultSet r,Set<String>c,String n,int d){try{String v=value(r,c,n);return v.isBlank()?d:Integer.parseInt(v);}catch(Exception e){return d;}}
    private static Integer intOrNull(ResultSet r,Set<String>c,String n){String v=value(r,c,n);try{return v.isBlank()?null:Integer.parseInt(v);}catch(Exception e){return null;}}
    private static long scalar(JdbcTemplate jt,String sql){Long n=jt.queryForObject(sql,Long.class);return n==null?0:n;}
    private static String stableId(String kind,String value){return UUID.nameUUIDFromBytes(("hlc2:"+kind+":"+value).getBytes(StandardCharsets.UTF_8)).toString();}
    private static String normalize(String s){return s==null?"":s.replace('\\','/').replaceAll("^/+","").trim();}
    private static String trimDot(String s){String v=s==null?"":s.trim();while(v.startsWith("."))v=v.substring(1);return v;}
    private static boolean isArchive(String s){String v=(s==null?"":s).toLowerCase(Locale.ROOT);return v.endsWith(".zip")||v.endsWith(".7z")||v.endsWith(".rar")||v.endsWith(".cbz")||v.endsWith(".jar");}
    private static String firstNonBlank(String...v){for(String s:v)if(s!=null&&!s.isBlank())return s.trim();return "";}
    private static String blankTo(String s,String d){return s==null||s.isBlank()?d:s;}
    private record MigrationStats(long books,long authors,long genres){}
}
