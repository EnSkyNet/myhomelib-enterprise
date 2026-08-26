package com.myhomelibcorp.benchmark;

import com.myhomelibcorp.reader.api.FileBookSource;
import com.myhomelibcorp.reader.api.ParseOptions;
import com.myhomelibcorp.reader.api.ReaderDocument;
import com.myhomelibcorp.reader.format.epub.EpubParser;
import com.myhomelibcorp.reader.format.fb2.Fb2StreamingParser;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.TextField;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.store.ByteBuffersDirectory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Stage 24 opt-in performance regression suite.
 *
 * It is deliberately skipped during normal unit tests. Run with:
 *   ./mvnw -pl myhomelib-benchmark -am test -Pperformance
 * or override catalogue sizes with -Dmhl.performance.sizes=100000,500000,1000000.
 */
@EnabledIfSystemProperty(named = "mhl.performance", matches = "true")
class PerformanceBaselineTest {

    private static final int READER_TEXT_MB = Integer.getInteger("mhl.performance.readerMb", 8);
    private static final Path REPORT = Path.of(System.getProperty(
            "mhl.performance.report", "target/performance-baseline-jvm.json"));

    @Test
    void performanceGuardrails() throws Exception {
        List<Integer> sizes = parseSizes(System.getProperty("mhl.performance.sizes", "100000"));
        List<Map<String, Object>> catalogues = new ArrayList<>();
        for (int size : sizes) {
            catalogues.add(benchmarkCatalogue(size));
        }
        Map<String, Object> reader = benchmarkReader();
        Map<String, Object> lucene = benchmarkLucene(Math.min(250_000, Math.max(100_000, sizes.getLast())));

        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema", 1);
        report.put("generatedAt", Instant.now().toString());
        report.put("java", Runtime.version().toString());
        report.put("catalogues", catalogues);
        report.put("reader", reader);
        report.put("lucene", lucene);
        report.put("gc", gcSnapshot());
        Files.createDirectories(REPORT.toAbsolutePath().getParent());
        Files.writeString(REPORT, Json.write(report), StandardCharsets.UTF_8);

        for (Map<String, Object> result : catalogues) {
            int size = (Integer) result.get("books");
            double authorMs = (Double) result.get("authorsInitialMs");
            double pageMs = (Double) result.get("firstPageMs");
            double importRate = (Double) result.get("importProbeBooksPerSec");
            assertTrue(authorMs <= threshold(size, 500, 1_500, 3_000), "author initial regression at " + size);
            assertTrue(pageMs <= threshold(size, 250, 500, 900), "first page regression at " + size);
            assertTrue(importRate >= threshold(size, 3_000, 2_500, 2_000), "write throughput regression at " + size);
        }
        assertTrue((Double) reader.get("fb2ParseMs") < 15_000, "huge FB2 parse regression");
        assertTrue((Double) reader.get("epubParseMs") < 15_000, "huge EPUB parse regression");
        assertTrue((Long) reader.get("peakHeapDeltaBytes") < 768L * 1024 * 1024, "reader heap regression");
        assertTrue((Double) lucene.get("queryMs") < 1_000, "Lucene query regression");
    }

    private static Map<String, Object> benchmarkCatalogue(int books) throws Exception {
        Path db = Files.createTempFile("mhl-stage24-" + books + "-", ".db");
        try {
            String url = "jdbc:sqlite:" + db.toAbsolutePath();
            long migrationStart = System.nanoTime();
            Flyway.configure().dataSource(url, null, null).locations("classpath:db/migration")
                    .baselineOnMigrate(true).load().migrate();
            double migrationMs = elapsedMs(migrationStart);
            try (Connection c = DriverManager.getConnection(url)) {
                configure(c);
                generateFixture(c, books);
                double authorMs = medianMs(3, () -> queryAll(c, """
                        SELECT a.id, COUNT(DISTINCT b.id)
                        FROM authors a
                        JOIN book_authors ba ON ba.author_id=a.id
                        JOIN books b ON b.id=ba.book_id
                        WHERE b.deleted=0
                          AND SUBSTR((CASE WHEN TRIM(COALESCE(a.last_name,''))<>'' THEN TRIM(a.last_name)
                                           WHEN TRIM(COALESCE(a.first_name,''))<>'' THEN TRIM(a.first_name)
                                           ELSE TRIM(COALESCE(a.middle_name,'')) END),1,1) IN ('A','a')
                        GROUP BY a.id
                        """));
                double pageMs = medianMs(5, () -> queryAll(c,
                        "SELECT id,title FROM books WHERE deleted=0 ORDER BY title,id LIMIT 100"));
                double libIdMs = medianMs(5, () -> queryAll(c,
                        "SELECT id FROM books WHERE lib_id='LIB-000050000'"));
                double writeRate = importProbe(c, books, 20_000);
                Map<String, Object> result = new LinkedHashMap<>();
                result.put("books", books);
                result.put("migrationMs", migrationMs);
                result.put("authorsInitialMs", authorMs);
                result.put("firstPageMs", pageMs);
                result.put("libIdMs", libIdMs);
                result.put("importProbeBooksPerSec", writeRate);
                return result;
            }
        } finally {
            Files.deleteIfExists(db);
            Files.deleteIfExists(Path.of(db + "-wal"));
            Files.deleteIfExists(Path.of(db + "-shm"));
        }
    }

    private static void configure(Connection c) throws Exception {
        c.createStatement().execute("PRAGMA journal_mode=MEMORY");
        c.createStatement().execute("PRAGMA synchronous=OFF");
        c.createStatement().execute("PRAGMA temp_store=MEMORY");
        c.createStatement().execute("PRAGMA cache_size=-65536");
        c.createStatement().execute("PRAGMA foreign_keys=OFF");
    }

    private static void generateFixture(Connection c, int books) throws Exception {
        int authors = Math.max(1, books / 3);
        c.createStatement().executeUpdate("CREATE TEMP TABLE benchmark_digits(d INTEGER PRIMARY KEY)");
        c.createStatement().executeUpdate("INSERT INTO benchmark_digits VALUES(0),(1),(2),(3),(4),(5),(6),(7),(8),(9)");
        c.createStatement().executeUpdate("CREATE TEMP TABLE benchmark_nums(n INTEGER PRIMARY KEY)");
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO benchmark_nums(n)
                SELECT d0.d+10*d1.d+100*d2.d+1000*d3.d+10000*d4.d+100000*d5.d
                FROM benchmark_digits d0,benchmark_digits d1,benchmark_digits d2,
                     benchmark_digits d3,benchmark_digits d4,benchmark_digits d5
                WHERE d0.d+10*d1.d+100*d2.d+1000*d3.d+10000*d4.d+100000*d5.d < ?
                """)) {
            ps.setInt(1, books);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO authors(id,first_name,middle_name,last_name,search_name)
                SELECT printf('a%07d',n),'First'||(n%1000),'',
                       char(65+(n%26))||'uthor'||printf('%07d',n),
                       lower(char(65+(n%26))||'uthor'||printf('%07d',n)||' First'||(n%1000))
                FROM benchmark_nums WHERE n < ?
                """)) {
            ps.setInt(1, authors);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO books(id,title,file_name,folder,language,file_size,keywords,annotation,rate,progress,
                                  update_date,deleted,local,collection_root,format,author_sort,publisher,year,lib_id)
                SELECT printf('b%09d',n),'Book title '||printf('%08d',n)||' benchmark token'||(n%997),
                       'book'||printf('%09d',n)||'.fb2','/library','uk',123456,'benchmark,topic',
                       'Synthetic annotation',n%6,n%100,'2026-08-25',0,n%4=0,'/library','FB2',
                       char(65+((n%?)%26))||'uthor'||printf('%07d',n%?),'Publisher',1950+n%77,
                       'LIB-'||printf('%09d',n)
                FROM benchmark_nums WHERE n < ?
                """)) {
            ps.setInt(1, authors);
            ps.setInt(2, authors);
            ps.setInt(3, books);
            ps.executeUpdate();
        }
        try (PreparedStatement ps = c.prepareStatement("""
                INSERT INTO book_authors(book_id,author_id)
                SELECT printf('b%09d',n),printf('a%07d',n%?) FROM benchmark_nums WHERE n < ?
                """)) {
            ps.setInt(1, authors);
            ps.setInt(2, books);
            ps.executeUpdate();
        }
    }

    private static double importProbe(Connection c, int baseBooks, int rows) throws Exception {
        int authors = Math.max(1, baseBooks / 3);
        boolean auto = c.getAutoCommit();
        c.setAutoCommit(false);
        long started = System.nanoTime();
        try (PreparedStatement b = c.prepareStatement("""
                INSERT INTO books(id,title,file_name,folder,language,file_size,deleted,local,collection_root,format,author_sort,year,lib_id)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                """); PreparedStatement ba = c.prepareStatement("INSERT INTO book_authors(book_id,author_id) VALUES(?,?)")) {
            for (int i = 0; i < rows; i++) {
                long n = 10_000_000L + baseBooks + i;
                int aid = (int) (n % authors);
                String id = "p" + n;
                b.setString(1, id); b.setString(2, "Probe " + n); b.setString(3, "probe.fb2");
                b.setString(4, "/probe"); b.setString(5, "uk"); b.setLong(6, 123456); b.setInt(7, 0); b.setInt(8, 0);
                b.setString(9, "/probe"); b.setString(10, "FB2"); b.setString(11, "Author " + aid);
                b.setInt(12, 2026); b.setString(13, "PROBE-" + n); b.addBatch();
                ba.setString(1, id); ba.setString(2, String.format(Locale.ROOT, "a%07d", aid)); ba.addBatch();
                if ((i + 1) % 1_000 == 0) { b.executeBatch(); ba.executeBatch(); }
            }
            b.executeBatch(); ba.executeBatch();
        }
        double ms = elapsedMs(started);
        c.rollback();
        c.setAutoCommit(auto);
        return rows / (ms / 1000.0);
    }

    private static Map<String, Object> benchmarkReader() throws Exception {
        Path dir = Files.createTempDirectory("mhl-stage24-reader-");
        Path fb2 = dir.resolve("huge.fb2");
        Path epub = dir.resolve("huge.epub");
        try {
            createFb2(fb2, READER_TEXT_MB);
            createEpub(epub, READER_TEXT_MB);
            PeakHeapSampler sampler = new PeakHeapSampler();
            sampler.start();
            long beforeGc = totalGcCount();
            long started = System.nanoTime();
            ReaderDocument fb2Doc = new Fb2StreamingParser().parse(new FileBookSource(fb2), ParseOptions.withoutImages());
            double fb2Ms = elapsedMs(started);
            long fb2Chars = fb2Doc.totalTextLength();
            started = System.nanoTime();
            ReaderDocument epubDoc = new EpubParser().parse(new FileBookSource(epub), ParseOptions.withoutImages());
            double epubMs = elapsedMs(started);
            long epubChars = epubDoc.totalTextLength();
            long peakDelta = sampler.stopAndGetDelta();
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("fixtureMb", READER_TEXT_MB);
            out.put("fb2ParseMs", fb2Ms);
            out.put("fb2Chars", fb2Chars);
            out.put("epubParseMs", epubMs);
            out.put("epubChars", epubChars);
            out.put("peakHeapDeltaBytes", peakDelta);
            out.put("gcCollectionsDelta", totalGcCount() - beforeGc);
            return out;
        } finally {
            Files.deleteIfExists(fb2); Files.deleteIfExists(epub); Files.deleteIfExists(dir);
        }
    }

    private static Map<String, Object> benchmarkLucene(int documents) throws Exception {
        PeakHeapSampler sampler = new PeakHeapSampler();
        sampler.start();
        long indexStart = System.nanoTime();
        double queryMs;
        try (ByteBuffersDirectory directory = new ByteBuffersDirectory(); StandardAnalyzer analyzer = new StandardAnalyzer();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer))) {
            for (int i = 0; i < documents; i++) {
                Document d = new Document();
                d.add(new StringField("id", "b" + i, Field.Store.YES));
                d.add(new TextField("title", "benchmark title token" + (i % 997), Field.Store.NO));
                d.add(new TextField("authors", "author" + (i % 10000), Field.Store.NO));
                writer.addDocument(d);
            }
            writer.commit();
            double indexMs = elapsedMs(indexStart);
            try (DirectoryReader reader = DirectoryReader.open(writer)) {
                IndexSearcher searcher = new IndexSearcher(reader);
                long q = System.nanoTime();
                searcher.search(new TermQuery(new Term("title", "token42")), 100);
                queryMs = elapsedMs(q);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("documents", documents);
            out.put("indexMs", indexMs);
            out.put("queryMs", queryMs);
            out.put("peakHeapDeltaBytes", sampler.stopAndGetDelta());
            return out;
        }
    }

    private static void createFb2(Path file, int mb) throws IOException {
        int repeats = mb * 1024;
        try (BufferedWriter w = Files.newBufferedWriter(file, StandardCharsets.UTF_8)) {
            w.write("<?xml version=\"1.0\" encoding=\"UTF-8\"?><FictionBook><description><title-info><book-title>Stage24</book-title><lang>uk</lang></title-info></description><body><section><title><p>Benchmark</p></title>");
            String para = "<p>Великий тестовий абзац для перевірки потокового читання без повної byte-array копії. benchmark performance reader.</p>";
            for (int i = 0; i < repeats; i++) w.write(para);
            w.write("</section></body></FictionBook>");
        }
    }

    private static void createEpub(Path file, int mb) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(file))) {
            put(zip, "mimetype", "application/epub+zip");
            put(zip, "META-INF/container.xml", "<?xml version=\"1.0\"?><container xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\" version=\"1.0\"><rootfiles><rootfile full-path=\"OEBPS/content.opf\" media-type=\"application/oebps-package+xml\"/></rootfiles></container>");
            put(zip, "OEBPS/content.opf", "<?xml version=\"1.0\"?><package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\"><metadata xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>Stage24</dc:title><dc:creator>Benchmark</dc:creator><dc:language>en</dc:language></metadata><manifest><item id=\"c1\" href=\"c1.xhtml\" media-type=\"application/xhtml+xml\"/></manifest><spine><itemref idref=\"c1\"/></spine></package>");
            zip.putNextEntry(new ZipEntry("OEBPS/c1.xhtml"));
            zip.write("<?xml version=\"1.0\"?><html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>Benchmark</h1>".getBytes(StandardCharsets.UTF_8));
            byte[] p = "<p>Large EPUB benchmark paragraph for streaming reader performance and text storage.</p>".getBytes(StandardCharsets.UTF_8);
            for (int i = 0; i < mb * 1024; i++) zip.write(p);
            zip.write("</body></html>".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
    }

    private static void put(ZipOutputStream zip, String name, String value) throws IOException {
        zip.putNextEntry(new ZipEntry(name));
        zip.write(value.getBytes(StandardCharsets.UTF_8));
        zip.closeEntry();
    }

    private static void queryAll(Connection c, String sql) throws Exception {
        try (var statement = c.createStatement(); ResultSet rs = statement.executeQuery(sql)) {
            while (rs.next()) { /* materialize result path */ }
        }
    }

    private static double medianMs(int count, CheckedRunnable action) throws Exception {
        double[] values = new double[count];
        action.run();
        for (int i = 0; i < count; i++) {
            long t = System.nanoTime(); action.run(); values[i] = elapsedMs(t);
        }
        java.util.Arrays.sort(values);
        return values[values.length / 2];
    }

    private static List<Integer> parseSizes(String value) {
        List<Integer> out = new ArrayList<>();
        for (String part : value.split(",")) out.add(Integer.parseInt(part.trim()));
        return out;
    }

    private static double threshold(int size, double small, double medium, double large) {
        if (size <= 100_000) return small;
        if (size <= 500_000) return medium;
        return large;
    }

    private static double elapsedMs(long started) { return (System.nanoTime() - started) / 1_000_000.0; }
    private static long totalGcCount() { return ManagementFactory.getGarbageCollectorMXBeans().stream().mapToLong(GarbageCollectorMXBean::getCollectionCount).filter(v -> v >= 0).sum(); }
    private static Map<String, Object> gcSnapshot() {
        Map<String, Object> out = new LinkedHashMap<>();
        for (GarbageCollectorMXBean b : ManagementFactory.getGarbageCollectorMXBeans()) {
            out.put(b.getName(), Map.of("count", b.getCollectionCount(), "timeMs", b.getCollectionTime()));
        }
        return out;
    }

    @FunctionalInterface private interface CheckedRunnable { void run() throws Exception; }

    private static final class PeakHeapSampler {
        private final MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        private volatile boolean running;
        private volatile long peak;
        private long baseline;
        private Thread thread;
        void start() {
            baseline = bean.getHeapMemoryUsage().getUsed(); peak = baseline; running = true;
            thread = Thread.ofPlatform().daemon().name("stage24-heap-sampler").start(() -> {
                while (running) {
                    peak = Math.max(peak, bean.getHeapMemoryUsage().getUsed());
                    try { Thread.sleep(10); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                }
            });
        }
        long stopAndGetDelta() throws InterruptedException {
            running = false; if (thread != null) thread.join(500);
            return Math.max(0, peak - baseline);
        }
    }

    private static final class Json {
        static String write(Object value) {
            StringBuilder b = new StringBuilder(); append(b, value, 0); return b.toString();
        }
        private static void append(StringBuilder b, Object v, int indent) {
            if (v == null) { b.append("null"); return; }
            if (v instanceof String s) { b.append('"').append(s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")).append('"'); return; }
            if (v instanceof Number || v instanceof Boolean) { b.append(v); return; }
            if (v instanceof Map<?, ?> m) {
                b.append("{\n"); int i=0; for (var e:m.entrySet()) { if(i++>0)b.append(",\n"); b.append("  ".repeat(indent+1)); append(b,String.valueOf(e.getKey()),indent+1); b.append(": "); append(b,e.getValue(),indent+1); }
                b.append('\n').append("  ".repeat(indent)).append('}'); return;
            }
            if (v instanceof Iterable<?> it) {
                b.append("[\n"); int i=0; for(Object x:it){if(i++>0)b.append(",\n");b.append("  ".repeat(indent+1));append(b,x,indent+1);} b.append('\n').append("  ".repeat(indent)).append(']'); return;
            }
            append(b,String.valueOf(v),indent);
        }
    }
}
