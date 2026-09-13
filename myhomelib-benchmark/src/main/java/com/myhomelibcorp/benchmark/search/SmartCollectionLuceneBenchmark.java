package com.myhomelibcorp.benchmark.search;

import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.Document;
import org.apache.lucene.document.IntPoint;
import org.apache.lucene.document.NumericDocValuesField;
import org.apache.lucene.document.SortedDocValuesField;
import org.apache.lucene.document.StringField;
import org.apache.lucene.document.Field;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.IndexWriter;
import org.apache.lucene.index.IndexWriterConfig;
import org.apache.lucene.index.Term;
import org.apache.lucene.search.BooleanClause;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.MatchAllDocsQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.Sort;
import org.apache.lucene.search.SortField;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.search.TopDocs;
import org.apache.lucene.store.FSDirectory;
import org.apache.lucene.util.BytesRef;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reproducible MHL-114/MHL-115 Lucene performance harness.
 *
 * <p>Default corpus is 500k synthetic deterministic documents. The tool intentionally records
 * measurements instead of encoding a latency SLA. It covers cold/warm first-page search,
 * AND/OR 5/20-rule Boolean filters, numeric range, DocValues sort and bounded maxResults.</p>
 */
public final class SmartCollectionLuceneBenchmark {
    private static final int DEFAULT_DOCUMENTS = 500_000;
    private static final int DEFAULT_RUNS = 7;
    private static final int FIRST_PAGE = 100;
    private static final int BOUNDED_MAX_RESULTS = 10_000;

    private SmartCollectionLuceneBenchmark() { }

    public static void main(String[] args) throws Exception {
        Config config = Config.parse(args);
        Files.createDirectories(config.indexDir());
        boolean existingIndex;
        try (FSDirectory directory = FSDirectory.open(config.indexDir())) {
            existingIndex = DirectoryReader.indexExists(directory);
        }
        if (!config.reuseIndex() || !existingIndex) {
            rebuildCorpus(config.indexDir(), config.documents());
        }

        List<Result> results = runQueries(config);
        writeCsv(config.output(), results, config);
        System.out.println("Benchmark complete: " + config.output().toAbsolutePath().normalize());
    }

    private static void rebuildCorpus(Path indexDir, int documents) throws IOException {
        try (FSDirectory directory = FSDirectory.open(indexDir);
             StandardAnalyzer analyzer = new StandardAnalyzer();
             IndexWriter writer = new IndexWriter(directory, new IndexWriterConfig(analyzer)
                     .setOpenMode(IndexWriterConfig.OpenMode.CREATE))) {
            for (int i = 0; i < documents; i++) {
                Document doc = new Document();
                String id = String.format(Locale.ROOT, "book-%07d", i);
                String title = String.format(Locale.ROOT, "title-%07d", i);
                int year = 1950 + (i % 77);
                doc.add(new StringField("id", id, Field.Store.NO));
                doc.add(new StringField("title_exact", title, Field.Store.NO));
                doc.add(new SortedDocValuesField("title_sort", new BytesRef(title)));
                doc.add(new IntPoint("year_num", year));
                doc.add(new NumericDocValuesField("year_sort", year));
                // Common deterministic tags deliberately make every Boolean clause participate.
                for (int tag = 0; tag < 20; tag++) {
                    doc.add(new StringField("tag", "k" + tag, Field.Store.NO));
                }
                writer.addDocument(doc);
                if ((i + 1) % 50_000 == 0) {
                    System.out.println("Indexed " + (i + 1) + " / " + documents);
                }
            }
            writer.commit();
        }
    }

    private static List<Result> runQueries(Config config) throws IOException {
        List<Result> results = new ArrayList<>();
        try (FSDirectory directory = FSDirectory.open(config.indexDir());
             DirectoryReader reader = DirectoryReader.open(directory)) {
            IndexSearcher searcher = new IndexSearcher(reader);
            // Cold means the first execution after opening a fresh DirectoryReader/searcher.
            results.add(measure("cold_first_page", searcher, new MatchAllDocsQuery(), FIRST_PAGE,
                    new Sort(new SortField("title_sort", SortField.Type.STRING)), 1));
            results.add(measure("warm_first_page", searcher, new MatchAllDocsQuery(), FIRST_PAGE,
                    new Sort(new SortField("title_sort", SortField.Type.STRING)), config.runs()));
            results.add(measure("and_5_rules", searcher, booleanTags(5, BooleanClause.Occur.MUST), FIRST_PAGE, null, config.runs()));
            results.add(measure("or_5_rules", searcher, booleanTags(5, BooleanClause.Occur.SHOULD), FIRST_PAGE, null, config.runs()));
            results.add(measure("and_20_rules", searcher, booleanTags(20, BooleanClause.Occur.MUST), FIRST_PAGE, null, config.runs()));
            results.add(measure("or_20_rules", searcher, booleanTags(20, BooleanClause.Occur.SHOULD), FIRST_PAGE, null, config.runs()));
            results.add(measure("numeric_range", searcher, IntPoint.newRangeQuery("year_num", 1990, 2010), FIRST_PAGE, null, config.runs()));
            results.add(measure("docvalues_sort", searcher, new MatchAllDocsQuery(), FIRST_PAGE,
                    new Sort(new SortField("year_sort", SortField.Type.LONG), new SortField("title_sort", SortField.Type.STRING)), config.runs()));
            results.add(measure("bounded_max_results", searcher, new MatchAllDocsQuery(), BOUNDED_MAX_RESULTS,
                    new Sort(new SortField("title_sort", SortField.Type.STRING)), config.runs()));
        }
        return results;
    }

    private static Query booleanTags(int count, BooleanClause.Occur occur) {
        BooleanQuery.Builder builder = new BooleanQuery.Builder();
        for (int i = 0; i < count; i++) {
            builder.add(new TermQuery(new Term("tag", "k" + i)), occur);
        }
        if (occur == BooleanClause.Occur.SHOULD) builder.setMinimumNumberShouldMatch(1);
        return builder.build();
    }

    private static Result measure(String scenario, IndexSearcher searcher, Query query, int maxResults,
                                  Sort sort, int runs) throws IOException {
        long[] elapsed = new long[Math.max(1, runs)];
        long totalHits = -1;
        String totalHitsRelation = "UNKNOWN";
        long heapPeak = 0L;
        long rssPeak = 0L;
        long gcBefore = gcCollections();
        long gcTimeBefore = gcTimeMs();
        for (int i = 0; i < elapsed.length; i++) {
            long started = System.nanoTime();
            TopDocs docs = sort == null ? searcher.search(query, maxResults) : searcher.search(query, maxResults, sort);
            elapsed[i] = System.nanoTime() - started;
            totalHits = docs.totalHits.value;
            totalHitsRelation = docs.totalHits.relation.name();
            heapPeak = Math.max(heapPeak, heapUsed());
            rssPeak = Math.max(rssPeak, rssBytes());
        }
        java.util.Arrays.sort(elapsed);
        long medianMicros = elapsed[elapsed.length / 2] / 1_000L;
        long minMicros = elapsed[0] / 1_000L;
        long maxMicros = elapsed[elapsed.length - 1] / 1_000L;
        return new Result(scenario, elapsed.length, maxResults, totalHits, totalHitsRelation,
                minMicros, medianMicros, maxMicros, heapPeak, rssPeak,
                Math.max(0L, gcCollections() - gcBefore), Math.max(0L, gcTimeMs() - gcTimeBefore));
    }

    private static void writeCsv(Path output, List<Result> results, Config config) throws IOException {
        Path parent = output.toAbsolutePath().normalize().getParent();
        if (parent != null) Files.createDirectories(parent);
        StringBuilder csv = new StringBuilder();
        csv.append("generated_at,documents,index_dir,scenario,runs,max_results,reported_total_hits,total_hits_relation,min_us,median_us,max_us,heap_peak_bytes,rss_peak_bytes,gc_collections,gc_time_ms\n");
        for (Result result : results) {
            csv.append(Instant.now()).append(',')
                    .append(config.documents()).append(',')
                    .append(csv(config.indexDir().toAbsolutePath().normalize().toString())).append(',')
                    .append(result.scenario()).append(',')
                    .append(result.runs()).append(',')
                    .append(result.maxResults()).append(',')
                    .append(result.totalHits()).append(',')
                    .append(result.totalHitsRelation()).append(',')
                    .append(result.minMicros()).append(',')
                    .append(result.medianMicros()).append(',')
                    .append(result.maxMicros()).append(',')
                    .append(result.heapPeakBytes()).append(',')
                    .append(result.rssPeakBytes()).append(',')
                    .append(result.gcCollections()).append(',')
                    .append(result.gcTimeMs()).append('\n');
        }
        Files.writeString(output, csv.toString(), StandardCharsets.UTF_8);
    }

    private static String csv(String value) {
        return '"' + value.replace("\"", "\"\"") + '"';
    }

    private static long heapUsed() {
        MemoryMXBean bean = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = bean.getHeapMemoryUsage();
        return Math.max(0L, heap.getUsed());
    }

    private static long gcCollections() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> bean.getCollectionCount()).filter(value -> value >= 0).sum();
    }

    private static long gcTimeMs() {
        return ManagementFactory.getGarbageCollectorMXBeans().stream()
                .mapToLong(bean -> bean.getCollectionTime()).filter(value -> value >= 0).sum();
    }

    private static long rssBytes() {
        Path status = Path.of("/proc/self/status");
        if (!Files.isRegularFile(status)) return -1L;
        try {
            for (String line : Files.readAllLines(status, StandardCharsets.UTF_8)) {
                if (!line.startsWith("VmRSS:")) continue;
                String digits = line.replaceAll("[^0-9]", "");
                return digits.isBlank() ? -1L : Long.parseLong(digits) * 1024L;
            }
        } catch (Exception ignored) { }
        return -1L;
    }

    private record Result(String scenario, int runs, int maxResults, long totalHits, String totalHitsRelation,
                          long minMicros, long medianMicros, long maxMicros,
                          long heapPeakBytes, long rssPeakBytes, long gcCollections, long gcTimeMs) { }

    private record Config(Path indexDir, Path output, int documents, int runs, boolean reuseIndex) {
        static Config parse(String[] args) {
            Path index = Path.of("target", "smart-collection-benchmark-index");
            Path output = Path.of("target", "smart-collection-benchmark.csv");
            int documents = DEFAULT_DOCUMENTS;
            int runs = DEFAULT_RUNS;
            boolean reuse = false;
            for (int i = 0; i < args.length; i++) {
                switch (args[i]) {
                    case "--index" -> index = Path.of(requireValue(args, ++i, "--index"));
                    case "--output" -> output = Path.of(requireValue(args, ++i, "--output"));
                    case "--documents" -> documents = positiveInt(requireValue(args, ++i, "--documents"), "--documents");
                    case "--runs" -> runs = positiveInt(requireValue(args, ++i, "--runs"), "--runs");
                    case "--reuse-index" -> reuse = true;
                    default -> throw new IllegalArgumentException("Unknown argument: " + args[i]);
                }
            }
            return new Config(index, output, documents, runs, reuse);
        }

        private static String requireValue(String[] args, int index, String option) {
            if (index >= args.length) throw new IllegalArgumentException("Missing value for " + option);
            return args[index];
        }

        private static int positiveInt(String raw, String option) {
            int value = Integer.parseInt(raw);
            if (value <= 0) throw new IllegalArgumentException(option + " must be > 0");
            return value;
        }
    }
}
