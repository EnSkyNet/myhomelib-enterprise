package com.myhomelibcorp.infrastructure.collection.legacy;

import com.myhomelibcorp.application.port.out.collection.LegacyCollectionAttachPort;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionStorageManager;
import com.myhomelibcorp.application.port.out.infrastructure.CollectionDatabaseOwnershipPort;
import com.myhomelibcorp.application.util.SafeFilePublication;
import com.myhomelibcorp.application.port.out.repository.CollectionRepository;
import com.myhomelibcorp.application.service.CollectionLifecycleService;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.service.LanguageResolver;
import com.myhomelibcorp.infrastructure.collection.CollectionManager;
import com.myhomelibcorp.shared.util.AppPaths;
import lombok.extern.slf4j.Slf4j;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.AuthorSearchNameNormalizer;
import com.myhomelibcorp.infrastructure.persistence.sqlite.helper.KeywordIndexSupport;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/** Attaches a modern SQLite collection or losslessly migrates an original Delphi .hlc2 into the modern schema. */
@Component
@Slf4j
public class SqliteLegacyCollectionAttachAdapter implements LegacyCollectionAttachPort {
    private final CollectionRepository collectionRepository;
    private final CollectionLifecycleService lifecycle;
    private final CollectionManager collectionManager;
    private final CollectionStorageManager storageManager;
    private final CollectionDatabaseOwnershipPort databaseOwnership;

    /** Production constructor — Spring uses this one. */
    @Autowired
    public SqliteLegacyCollectionAttachAdapter(CollectionRepository collectionRepository,
                                               CollectionLifecycleService lifecycle,
                                               CollectionManager collectionManager,
                                               CollectionStorageManager storageManager,
                                               CollectionDatabaseOwnershipPort databaseOwnership) {
        this.collectionRepository = collectionRepository;
        this.lifecycle = lifecycle;
        this.collectionManager = collectionManager;
        this.storageManager = storageManager;
        this.databaseOwnership = databaseOwnership;
    }

    /** Compatibility constructor for isolated adapter tests. */
    public SqliteLegacyCollectionAttachAdapter(CollectionRepository collectionRepository,
                                               CollectionLifecycleService lifecycle,
                                               CollectionManager collectionManager,
                                               CollectionStorageManager storageManager) {
        this(collectionRepository, lifecycle, collectionManager, storageManager, null);
    }

    @Override
    public AttachResult attach(Path source, String collectionName, Path root) {
        Path safeSource;
        Path safeRoot;
        try {
            safeSource = SafeFilePublication.requireSafeRegularFile(source, "legacy collection attach source");
            safeRoot = SafeFilePublication.requireSafeDirectoryPath(root, "legacy collection root");
        } catch (java.io.IOException unsafePath) {
            throw new IllegalArgumentException("Небезпечний або недоступний шлях колекції", unsafePath);
        }

        SafeFilePublication.FileSnapshot sourceSnapshot = snapshotSource(safeSource);
        Collection previous = lifecycle.getCurrentCollection();
        Collection created = null;
        boolean ownsDatabase = false;
        try (Connection src = openReadOnly(safeSource)) {
            requireSourceUnchanged(safeSource, sourceSnapshot, "після відкриття SQLite");
            long sourceDataVersion = scalar(src, "PRAGMA data_version");
            Set<String> bookColumns = columns(src, "books");
            if (bookColumns.contains("id") && bookColumns.contains("file_name")) {
                // Validate/read the source completely before mutating registry or active lifecycle state.
                // Previously a missing/corrupt related table could fail only after save+switch, leaving
                // orphan metadata behind.
                long count = scalar(src, "SELECT COUNT(*) FROM books");
                long authors = scalar(src, "SELECT COUNT(*) FROM authors");
                long genres = scalar(src, "SELECT COUNT(*) FROM genres");
                requireSourceStable(src, safeSource, sourceSnapshot, sourceDataVersion,
                        "перед реєстрацією attached SQLite");
                created = collectionRepository.save(new Collection(UUID.randomUUID().toString(), collectionName, safeRoot,
                        safeSource.toString(), 0, null, null, null, "Attached SQLite collection"));
                if (databaseOwnership != null) {
                    databaseOwnership.setOwnership(created.getId(), CollectionDatabaseOwnershipPort.Ownership.EXTERNAL);
                }
                lifecycle.initializeCollection(created, true);
                return new AttachResult(created, count, authors, genres, false);
            }
            if (!bookColumns.contains("bookid") || !bookColumns.contains("title")) {
                throw new IllegalArgumentException("Файл не схожий на колекцію MyHomeLib (.hlc2)");
            }

            Path target = AppPaths.librariesDir().resolve(UUID.randomUUID() + ".db");
            ownsDatabase = true;
            created = collectionRepository.save(new Collection(UUID.randomUUID().toString(), collectionName, safeRoot,
                    target.toString(), 0, null, null, null, "Migrated from " + safeSource));
            if (databaseOwnership != null) {
                databaseOwnership.setOwnership(created.getId(), CollectionDatabaseOwnershipPort.Ownership.OWNED);
            }
            lifecycle.initializeCollection(created, false); // creates the modern schema via Flyway
            JdbcTemplate jt = collectionManager.getCurrentJdbcTemplate();
            MigrationStats stats = migrate(src, jt, safeRoot);
            requireSourceStable(src, safeSource, sourceSnapshot, sourceDataVersion,
                    "перед завершенням legacy migration");
            lifecycle.rebuildSearchIndex();
            return new AttachResult(created, stats.books, stats.authors, stats.genres, true);
        } catch (RuntimeException failure) {
            rollbackFailedAttach(created, previous, ownsDatabase, failure);
            throw failure;
        } catch (Exception checkedFailure) {
            IllegalStateException failure = new IllegalStateException(
                    "Не вдалося підключити .hlc2: " + checkedFailure.getMessage(), checkedFailure);
            rollbackFailedAttach(created, previous, ownsDatabase, failure);
            throw failure;
        }
    }

    private void rollbackFailedAttach(Collection failed, Collection previous, boolean ownsDatabase, RuntimeException original) {
        if (failed == null) return;
        try {
            Collection active = lifecycle.getCurrentCollection();
            if (sameCollection(active, failed)) {
                if (previous != null) lifecycle.initializeCollection(previous, true);
                else lifecycle.closeCollection();
            } else if (!sameCollection(active, previous)) {
                // Initialization may have failed closed. Restore only when lifecycle state is not
                // already the expected previous state.
                if (previous != null) lifecycle.initializeCollection(previous, true);
                else if (active != null) lifecycle.closeCollection();
            }
        } catch (RuntimeException restoreFailure) {
            original.addSuppressed(restoreFailure);
            log.error("Не вдалося відновити попередню колекцію після невдалого attach; metadata залишено для recovery",
                    restoreFailure);
            return;
        }

        try {
            storageManager.closeCollection(failed);
            if (ownsDatabase) storageManager.deletePhysicalFiles(failed);
        } catch (RuntimeException cleanupFailure) {
            original.addSuppressed(cleanupFailure);
            log.error("Не вдалося очистити ресурси невдалого attach {}; metadata залишено для recovery",
                    failed.getId(), cleanupFailure);
            return;
        }

        try {
            if (failed.getId() != null) collectionRepository.deleteById(failed.getId());
        } catch (RuntimeException registryFailure) {
            original.addSuppressed(registryFailure);
            log.error("Не вдалося видалити registry metadata невдалого attach {}", failed.getId(), registryFailure);
        }
    }

    private static boolean sameCollection(Collection left, Collection right) {
        if (left == right) return true;
        if (left == null || right == null || left.getId() == null || right.getId() == null) return false;
        return left.getId().equals(right.getId());
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
                long seriesId = longValue(rs, cols, "seriesid", 0L);
                String folder = normalize(value(rs, cols, "folder"));
                String originalFile = normalize(value(rs, cols, "filename"));
                String ext = trimDot(value(rs, cols, "ext"));
                String fileName = originalFile;
                if (!ext.isBlank() && !fileName.toLowerCase(Locale.ROOT).endsWith("." + ext.toLowerCase(Locale.ROOT))) fileName += "." + ext;
                String title = firstNonBlank(value(rs, cols, "title"), stem(fileName), libId, "legacy-" + oldId);
                boolean archive = isArchive(folder);
                String archiveEntry = archive ? fileName : "";
                String storedFile = archive ? folder : fileName;
                String storedFolder = archive ? "" : folder;
                String sourceUpdateDate = blankToNull(value(rs, cols, "updatedate"));
                String createdAt = sourceUpdateDate != null ? sourceUpdateDate : LocalDateTime.now().toString();
                Integer year = intOrNull(rs, cols, "pubyear");
                batch.add(new Object[]{id,title,series.getOrDefault(seriesId,""),intValue(rs,cols,"seqnumber",0),storedFile,storedFolder,
                        archiveEntry,LanguageResolver.resolveValue(value(rs,cols,"lang")),longValue(rs,cols,"booksize",0L),value(rs,cols,"keywords"),
                        value(rs,cols,"annotation"),intValue(rs,cols,"rate",0),intValue(rs,cols,"progress",0),sourceUpdateDate,value(rs,cols,"isbn"),
                        intValue(rs,cols,"isdeleted",0),intValue(rs,cols,"islocal",0),value(rs,cols,"review"),createdAt,root.toString(),year,
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
        } catch(SQLException e){ throw new IllegalStateException("Помилка перенесення серій", e); }
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
        }catch(SQLException e){ throw new IllegalStateException("Помилка перенесення авторів",e); }
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
        }catch(SQLException e){ throw new IllegalStateException("Помилка перенесення жанрів",e); }
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
        }catch(SQLException e){ throw new IllegalStateException("Помилка перенесення зв’язків авторів",e); }
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
        }catch(SQLException e){ throw new IllegalStateException("Помилка перенесення зв’язків жанрів",e); }
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
                file_size=excluded.file_size,keywords=excluded.keywords,annotation=excluded.annotation,update_date=COALESCE(excluded.update_date,books.update_date),
                isbn=excluded.isbn,deleted=excluded.deleted,local=excluded.local,collection_root=excluded.collection_root,year=excluded.year,
                publisher=excluded.publisher,lib_id=excluded.lib_id,library_rate=excluded.library_rate,translators=excluded.translators,city=excluded.city
                """,batch);
        Map<String, String> keywordProjection = new LinkedHashMap<>(batch.size());
        for (Object[] row : batch) keywordProjection.put((String) row[0], row[9] == null ? "" : row[9].toString());
        KeywordIndexSupport.replaceForBooks(jt, keywordProjection);
    }

    private static Connection openReadOnly(Path p) throws SQLException {
        org.sqlite.SQLiteConfig config = new org.sqlite.SQLiteConfig();
        config.setReadOnly(true);
        Connection connection = config.createConnection("jdbc:sqlite:" + p.toAbsolutePath().normalize());
        try {
            // sqlite-jdbc opens by pathname. Revalidate immediately after open so a path swapped
            // for a symlink/reparse entry during check->open never becomes a trusted source.
            SafeFilePublication.requireSafeRegularFile(p, "legacy collection attach opened source");
            return connection;
        } catch (java.io.IOException unsafeAfterOpen) {
            try { connection.close(); } catch (SQLException closeFailure) { unsafeAfterOpen.addSuppressed(closeFailure); }
            throw new SQLException("Legacy collection source became unsafe while opening", unsafeAfterOpen);
        }
    }

    private static SafeFilePublication.FileSnapshot snapshotSource(Path source) {
        try {
            return SafeFilePublication.snapshot(source, "legacy collection attach source");
        } catch (java.io.IOException failure) {
            throw new IllegalArgumentException("Не вдалося зафіксувати стан legacy collection source", failure);
        }
    }

    private static void requireSourceUnchanged(Path source,
                                               SafeFilePublication.FileSnapshot expected,
                                               String phase) throws SQLException {
        try {
            SafeFilePublication.FileSnapshot actual = SafeFilePublication.snapshot(
                    source, "legacy collection attach source " + phase);
            if (!expected.equals(actual)) {
                throw new SQLException("Legacy collection source changed " + phase);
            }
        } catch (java.io.IOException failure) {
            throw new SQLException("Legacy collection source became unsafe " + phase, failure);
        }
    }

    private static void requireSourceStable(Connection sourceConnection,
                                            Path source,
                                            SafeFilePublication.FileSnapshot expected,
                                            long expectedDataVersion,
                                            String phase) throws SQLException {
        long actualDataVersion = scalar(sourceConnection, "PRAGMA data_version");
        if (actualDataVersion != expectedDataVersion) {
            throw new SQLException("Legacy collection source changed concurrently " + phase);
        }
        requireSourceUnchanged(source, expected, phase);
    }
    private static Set<String> columns(Connection c,String table) throws SQLException { Set<String>s=new HashSet<>(); try(Statement st=c.createStatement();ResultSet r=st.executeQuery("PRAGMA table_info('"+table.replace("'","''")+"')")){while(r.next())s.add(r.getString("name").toLowerCase(Locale.ROOT));} return s; }
    private static boolean tableExists(Connection c,String table){try(PreparedStatement p=c.prepareStatement("SELECT 1 FROM sqlite_master WHERE type='table' AND lower(name)=lower(?)")){p.setString(1,table);try(ResultSet r=p.executeQuery()){return r.next();}}catch(SQLException e){throw new IllegalStateException("Не вдалося перевірити legacy table " + table,e);}}
    private static Set<String> columnNames(ResultSet resultSet) throws SQLException {
        Set<String> names = new HashSet<>();
        ResultSetMetaData metadata = resultSet.getMetaData();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            names.add(metadata.getColumnLabel(i).toLowerCase(Locale.ROOT));
        }
        return names;
    }

    private static String value(ResultSet resultSet, Set<String> columns, String name) {
        if (!columns.contains(name.toLowerCase(Locale.ROOT))) return "";
        try {
            Object value = resultSet.getObject(name);
            return value == null ? "" : String.valueOf(value).trim();
        } catch (SQLException labelFailure) {
            try {
                Object value = resultSet.getObject(findColumn(resultSet, name));
                return value == null ? "" : String.valueOf(value).trim();
            } catch (SQLException indexFailure) {
                indexFailure.addSuppressed(labelFailure);
                throw new IllegalStateException("Не вдалося прочитати legacy column '" + name + "'", indexFailure);
            }
        }
    }

    private static int findColumn(ResultSet resultSet, String name) throws SQLException {
        ResultSetMetaData metadata = resultSet.getMetaData();
        for (int i = 1; i <= metadata.getColumnCount(); i++) {
            if (metadata.getColumnLabel(i).equalsIgnoreCase(name)) return i;
        }
        throw new SQLException("Legacy column not found: " + name);
    }

    private static long longValue(ResultSet resultSet, Set<String> columns, String name, long defaultValue) {
        String value = value(resultSet, columns, name);
        if (value.isBlank()) return defaultValue;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException invalidNumber) {
            return defaultValue;
        }
    }

    private static int intValue(ResultSet resultSet, Set<String> columns, String name, int defaultValue) {
        String value = value(resultSet, columns, name);
        if (value.isBlank()) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalidNumber) {
            return defaultValue;
        }
    }

    private static Integer intOrNull(ResultSet resultSet, Set<String> columns, String name) {
        String value = value(resultSet, columns, name);
        if (value.isBlank()) return null;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException invalidNumber) {
            return null;
        }
    }
    private static long scalar(JdbcTemplate jt,String sql){Long n=jt.queryForObject(sql,Long.class);return n==null?0:n;}
    private static long scalar(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement(); ResultSet rows = statement.executeQuery(sql)) {
            return rows.next() ? rows.getLong(1) : 0L;
        }
    }
    private static String stableId(String kind,String value){return UUID.nameUUIDFromBytes(("hlc2:"+kind+":"+value).getBytes(StandardCharsets.UTF_8)).toString();}
    private static String normalize(String s){return s==null?"":s.replace('\\','/').replaceAll("^/+","").trim();}
    private static String trimDot(String s){String v=s==null?"":s.trim();while(v.startsWith("."))v=v.substring(1);return v;}
    private static boolean isArchive(String s){String v=(s==null?"":s).toLowerCase(Locale.ROOT);return v.endsWith(".zip")||v.endsWith(".7z")||v.endsWith(".rar")||v.endsWith(".cbz")||v.endsWith(".jar");}
    private static String firstNonBlank(String...v){for(String s:v)if(s!=null&&!s.isBlank())return s.trim();return "";}
    private static String blankTo(String s,String d){return s==null||s.isBlank()?d:s;}
    private static String blankToNull(String s){return s==null||s.isBlank()?null:s.trim();}
    private static String stem(String value){
        String v=value==null?"":value.trim();
        int slash=Math.max(v.lastIndexOf('/'),v.lastIndexOf('\\'));
        if(slash>=0)v=v.substring(slash+1);
        int dot=v.lastIndexOf('.');
        return dot>0?v.substring(0,dot):v;
    }
    private record MigrationStats(long books,long authors,long genres){}
}