package com.myhomelibcorp.organizer.analysis;

import com.myhomelibcorp.application.duplicate.fuzzy.FuzzyDuplicateDetector;
import com.myhomelibcorp.application.operation.LibraryOperationCoordinator;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.epub.optimizer.EpubOptimizer;
import com.myhomelibcorp.organizer.apply.ApplyLibraryOrganizationUseCase;
import com.myhomelibcorp.organizer.apply.ApplyResult;
import com.myhomelibcorp.organizer.apply.OrganizerCatalogPort;
import com.myhomelibcorp.organizer.apply.OrganizerOutputValidationPort;
import com.myhomelibcorp.organizer.journal.OrganizationItemState;
import com.myhomelibcorp.organizer.journal.OrganizationJournalItem;
import com.myhomelibcorp.organizer.journal.OrganizationJournalOperation;
import com.myhomelibcorp.organizer.journal.OrganizationJournalPort;
import com.myhomelibcorp.organizer.journal.OrganizationOperationState;
import com.myhomelibcorp.epub.optimizer.EpubOptimizationProfile;
import com.myhomelibcorp.organizer.detect.SignatureBookFormatDetector;
import com.myhomelibcorp.organizer.duplicate.DuplicateDecisionService;
import com.myhomelibcorp.organizer.fingerprint.BookFingerprintService;
import com.myhomelibcorp.organizer.metadata.MetadataNormalizationService;
import com.myhomelibcorp.organizer.metadata.OrganizerMetadataExtractor;
import com.myhomelibcorp.organizer.naming.CanonicalBookPathService;
import com.myhomelibcorp.organizer.plan.OrganizationPlan;
import com.myhomelibcorp.organizer.plan.PlanLibraryOrganizationUseCase;
import com.myhomelibcorp.organizer.policy.CanonicalFormatPolicy;
import com.myhomelibcorp.organizer.policy.OrganizerDuplicatePolicy;
import com.myhomelibcorp.organizer.policy.OrganizerProfile;
import com.myhomelibcorp.organizer.policy.OriginalRetentionPolicy;
import com.myhomelibcorp.organizer.policy.PdfPolicy;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDDocumentInformation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrganizerMixedLibraryAcceptanceTest {
    @TempDir Path temp;

    @Test
    void recursivelyAnalyzesMixedTreeExpandsMultiBookZipAndBuildsNonDestructivePlan() throws Exception {
        Path source = Files.createDirectories(temp.resolve("incoming"));
        Path nested = Files.createDirectories(source.resolve("legacy/deep/more"));
        Path destination = Files.createDirectories(temp.resolve("library"));

        writeFb2(nested.resolve("first.fb2"), "First", "Author One", "Saga", "1");
        writeOdt(source.resolve("office/book.odt"), "ODT Book", "ODT Author");
        writePdf(source.resolve("scans/book.pdf"), "PDF Book", "PDF Author");
        writeMobi(source.resolve("devices/book.mobi"), "MOBI Book", "MOBI Author");
        Files.createDirectories(source.resolve("notes"));
        Files.writeString(source.resolve("notes/Loose notes.txt"), "plain text", StandardCharsets.UTF_8);
        writeMultiBookZip(source.resolve("archives/two-books.zip"));

        Path occupied = destination.resolve("One Author/Saga/01 - First.fb2");
        Files.createDirectories(occupied.getParent());
        Files.writeString(occupied, "foreign existing file", StandardCharsets.UTF_8);

        AnalyzeLibraryOrganizationUseCase analyze = new AnalyzeLibraryOrganizationUseCase(
                new SignatureBookFormatDetector(), new OrganizerMetadataExtractor(), new MetadataNormalizationService(),
                new BookFingerprintService(null), new CanonicalFormatPolicy(List.of(), settings()),
                new CanonicalBookPathService(), new DuplicateDecisionService(new FuzzyDuplicateDetector()));
        OrganizerProfile profile = originalFormatProfile();

        OrganizationAnalysis analysis = analyze.analyze(source, destination, profile);

        assertThat(analysis.items()).hasSize(7); // 5 ordinary files + 2 logical entries from the multi-book ZIP
        assertThat(analysis.items()).anySatisfy(item -> {
            assertThat(item.extractedMetadata().rawTitle()).isEqualTo("First");
            assertThat(item.identity().authors()).extracting(a -> a.displayName()).contains("One Author");
            assertThat(item.proposedRelativePath().toString().replace('\\', '/'))
                    .isEqualTo("One Author/Saga/01 - First.fb2");
        });
        assertThat(analysis.items()).anySatisfy(item -> {
            assertThat(item.extractedMetadata().rawTitle()).isEqualTo("ODT Book");
            assertThat(item.proposedRelativePath().toString().replace('\\', '/')).isEqualTo("ODT Author/ODT Book.odt");
        });
        assertThat(analysis.items()).anySatisfy(item -> {
            assertThat(item.extractedMetadata().rawTitle()).isEqualTo("PDF Book");
            assertThat(item.proposedRelativePath().toString().replace('\\', '/')).isEqualTo("PDF Author/PDF Book.pdf");
        });
        assertThat(analysis.items()).anySatisfy(item -> {
            assertThat(item.extractedMetadata().rawTitle()).isEqualTo("MOBI Book");
            assertThat(item.proposedRelativePath().toString().replace('\\', '/')).isEqualTo("MOBI Author/MOBI Book.mobi");
        });
        assertThat(analysis.items()).anySatisfy(item -> {
            assertThat(item.detectedFormat().format()).isEqualTo("txt");
            assertThat(item.proposedRelativePath().toString().replace('\\', '/'))
                    .isEqualTo("_Невідомий автор/Loose notes.txt");
            assertThat(item.warnings()).anyMatch(w -> w.startsWith("METADATA_LIMITED:"));
        });

        List<OrganizerAnalysisItem> archiveItems = analysis.items().stream().filter(OrganizerAnalysisItem::archiveEntry).toList();
        assertThat(archiveItems).hasSize(2);
        assertThat(archiveItems).extracting(OrganizerAnalysisItem::sourceEntry)
                .containsExactlyInAnyOrder("alpha/book-a.fb2", "beta/book-b.fb2");

        OrganizationPlan plan = new PlanLibraryOrganizationUseCase(new CanonicalBookPathService()).plan(analysis, profile);
        assertThat(plan.actions()).hasSize(analysis.items().size());
        Set<Path> targets = plan.actions().stream().map(action -> action.targetPath().toAbsolutePath().normalize()).collect(Collectors.toSet());
        assertThat(targets).hasSize(plan.actions().size());
        assertThat(plan.actions()).noneSatisfy(action -> assertThat(action.targetPath()).isEqualTo(occupied));
        assertThat(plan.actions()).anySatisfy(action -> {
            if (action.sourcePath().getFileName().toString().equals("first.fb2")) {
                assertThat(action.targetPath().getFileName().toString()).startsWith("01 - First [");
                assertThat(action.issues()).anyMatch(issue -> "TARGET_COLLISION_RESOLVED".equals(issue.code()));
            }
        });

        assertThat(Files.readString(occupied, StandardCharsets.UTF_8)).isEqualTo("foreign existing file");
        assertThat(Files.exists(nested.resolve("first.fb2"))).isTrue(); // analysis/plan are dry-run only
    }


    @Test
    void mixedTreePlanAppliesToFilesystemAndRetainsMultiBookArchiveContainer() throws Exception {
        Path source = Files.createDirectories(temp.resolve("apply-incoming"));
        Path destination = Files.createDirectories(temp.resolve("apply-library"));
        writeFb2(source.resolve("nested/a/first.fb2"), "First", "Author One", "Saga", "1");
        writeOdt(source.resolve("nested/b/book.odt"), "ODT Book", "ODT Author");
        writePdf(source.resolve("nested/c/book.pdf"), "PDF Book", "PDF Author");
        writeMobi(source.resolve("nested/d/book.mobi"), "MOBI Book", "MOBI Author");
        Files.createDirectories(source.resolve("notes"));
        Files.writeString(source.resolve("notes/Loose notes.txt"), "plain text", StandardCharsets.UTF_8);
        Path archive = source.resolve("archives/two-books.zip");
        writeMultiBookZip(archive);

        OrganizationAnalysis analysis = analyzer().analyze(source, destination, originalFormatProfile());
        OrganizationPlan plan = new PlanLibraryOrganizationUseCase(new CanonicalBookPathService()).plan(analysis, originalFormatProfile());
        InMemoryJournal journal = new InMemoryJournal();

        ApplyResult result = applyUseCase(journal, List.of()).apply(plan, () -> false);

        assertThat(result.committed()).isEqualTo(plan.actions().size());
        assertThat(journal.operation(plan.operationId()).state()).isEqualTo(OrganizationOperationState.COMPLETED);
        assertThat(journal.items(plan.operationId())).allSatisfy(item -> assertThat(item.state()).isEqualTo(OrganizationItemState.COMMITTED));
        for (var action : plan.actions()) {
            assertThat(Files.isRegularFile(action.targetPath())).as("published target %s", action.targetPath()).isTrue();
            if (action.sourceEntry() == null || action.sourceEntry().isBlank()) {
                assertThat(Files.exists(action.sourcePath())).as("moved source %s", action.sourcePath()).isFalse();
            }
        }
        assertThat(Files.isRegularFile(archive)).isTrue();
        assertThat(Files.isRegularFile(destination.resolve("One Author/Saga/01 - First.fb2"))).isTrue();
        assertThat(Files.isRegularFile(destination.resolve("ODT Author/ODT Book.odt"))).isTrue();
        assertThat(Files.isRegularFile(destination.resolve("PDF Author/PDF Book.pdf"))).isTrue();
        assertThat(Files.isRegularFile(destination.resolve("MOBI Author/MOBI Book.mobi"))).isTrue();
        assertThat(Files.isRegularFile(destination.resolve("_Невідомий автор/Loose notes.txt"))).isTrue();
    }

    @Test
    void mixedTreeApplyRollsBackPublishedTargetWhenCatalogFailsAfterPublication() throws Exception {
        Path source = Files.createDirectories(temp.resolve("rollback-incoming"));
        Path destination = Files.createDirectories(temp.resolve("rollback-library"));
        Path original = source.resolve("nested/book.fb2");
        writeFb2(original, "Rollback Book", "Rollback Author", "", "");

        OrganizationAnalysis analysis = analyzer().analyze(source, destination, originalFormatProfile());
        OrganizationPlan plan = new PlanLibraryOrganizationUseCase(new CanonicalBookPathService()).plan(analysis, originalFormatProfile());
        assertThat(plan.actions()).singleElement();
        var action = plan.actions().getFirst();
        InMemoryJournal journal = new InMemoryJournal();
        OrganizerCatalogPort failingCatalog = new OrganizerCatalogPort() {
            @Override public boolean supports(com.myhomelibcorp.organizer.plan.OrganizationPlanItem ignored) { return true; }
            @Override public void afterFilesystemCommit(com.myhomelibcorp.organizer.plan.OrganizationPlanItem ignored) {
                throw new IllegalStateException("synthetic catalog publication failure");
            }
            @Override public void rollbackCatalog(com.myhomelibcorp.organizer.plan.OrganizationPlanItem ignored) { }
        };

        assertThatThrownBy(() -> applyUseCase(journal, List.of(failingCatalog)).apply(plan, () -> false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("synthetic catalog publication failure");

        assertThat(Files.isRegularFile(original)).isTrue();
        assertThat(Files.exists(action.targetPath())).isFalse();
        assertThat(journal.operation(plan.operationId()).state()).isEqualTo(OrganizationOperationState.FAILED);
        assertThat(journal.items(plan.operationId())).singleElement()
                .satisfies(item -> assertThat(item.state()).isEqualTo(OrganizationItemState.FAILED));
    }

    @Test
    void longUnicodeArchiveEntryNameDoesNotSplitSurrogatePairDuringMaterialization() throws Exception {
        Path source = Files.createDirectories(temp.resolve("unicode-incoming"));
        Path destination = Files.createDirectories(temp.resolve("unicode-library"));
        Path archive = source.resolve("unicode-books.zip");
        String longEntry = "😀" + "a".repeat(175) + ".fb2";
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(archive))) {
            putZip(zip, longEntry, fb2String("Unicode Archive", "Unicode Author", "", ""));
            putZip(zip, "second.fb2", fb2String("Second", "Second Author", "", ""));
        }

        AnalyzeLibraryOrganizationUseCase analyze = new AnalyzeLibraryOrganizationUseCase(
                new SignatureBookFormatDetector(), new OrganizerMetadataExtractor(), new MetadataNormalizationService(),
                new BookFingerprintService(null), new CanonicalFormatPolicy(List.of(), settings()),
                new CanonicalBookPathService(), new DuplicateDecisionService(new FuzzyDuplicateDetector()));

        OrganizationAnalysis analysis = analyze.analyze(source, destination, originalFormatProfile());

        assertThat(analysis.items()).hasSize(2);
        assertThat(analysis.items()).anySatisfy(item -> {
            assertThat(item.sourceEntry()).isEqualTo(longEntry);
            assertThat(item.extractedMetadata().rawTitle()).isEqualTo("Unicode Archive");
            assertThat(item.warnings()).noneMatch(w -> w.startsWith("ARCHIVE_ENTRY_FAILED:"));
        });
    }


    private AnalyzeLibraryOrganizationUseCase analyzer() {
        return new AnalyzeLibraryOrganizationUseCase(
                new SignatureBookFormatDetector(), new OrganizerMetadataExtractor(), new MetadataNormalizationService(),
                new BookFingerprintService(null), new CanonicalFormatPolicy(List.of(), settings()),
                new CanonicalBookPathService(), new DuplicateDecisionService(new FuzzyDuplicateDetector()));
    }

    private ApplyLibraryOrganizationUseCase applyUseCase(InMemoryJournal journal, List<OrganizerCatalogPort> catalogs) {
        EpubOptimizer unusedOptimizer = new EpubOptimizer(null, null, null, null, null, null, null, null, null, List.of());
        OrganizerOutputValidationPort validation = (file, format, maxBytes) -> { };
        return new ApplyLibraryOrganizationUseCase(List.of(), journal, new LibraryOperationCoordinator(), catalogs,
                unusedOptimizer, settings(), validation);
    }

    private OrganizerProfile originalFormatProfile() {
        return new OrganizerProfile("acceptance", "Acceptance", "{Author}/{?Series:{Series}/}",
                "{?Series:{SeriesNo:00} - }{Title}.{ext}", "original", PdfPolicy.KEEP_PDF,
                OriginalRetentionPolicy.KEEP_ORIGINAL, OrganizerDuplicatePolicy.KEEP_ALL,
                true, true, false, 2, 1, EpubOptimizationProfile.NONE);
    }

    private ApplicationSettingsPort settings() {
        return new ApplicationSettingsPort() {
            @Override public String get(String key, String defaultValue) { return defaultValue; }
            @Override public void put(String key, String value) { }
            @Override public void remove(String key) { }
            @Override public Map<String, String> findByPrefix(String prefix) { return Map.of(); }
        };
    }

    private void writeFb2(Path path, String title, String author, String series, String number) throws Exception {
        Files.createDirectories(path.getParent());
        String[] parts = author.split(" ", 2);
        String first = parts[0], last = parts.length > 1 ? parts[1] : "";
        String xml = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\"><description><title-info>"
                + "<author><first-name>" + first + "</first-name><last-name>" + last + "</last-name></author>"
                + "<book-title>" + title + "</book-title>"
                + (series.isBlank() ? "" : "<sequence name=\"" + series + "\" number=\"" + number + "\"/>")
                + "<lang>en</lang></title-info></description><body><section><p>text</p></section></body></FictionBook>";
        Files.writeString(path, xml, StandardCharsets.UTF_8);
    }

    private void writeOdt(Path path, String title, String author) throws Exception {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            putZip(zip, "mimetype", "application/vnd.oasis.opendocument.text");
            putZip(zip, "meta.xml", "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                    + "<office:document-meta xmlns:office=\"urn:oasis:names:tc:opendocument:xmlns:office:1.0\" "
                    + "xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><office:meta><dc:title>" + title
                    + "</dc:title><dc:creator>" + author + "</dc:creator></office:meta></office:document-meta>");
        }
    }

    private void writePdf(Path path, String title, String author) throws Exception {
        Files.createDirectories(path.getParent());
        try (PDDocument document = new PDDocument()) {
            PDDocumentInformation info = new PDDocumentInformation();
            info.setTitle(title); info.setAuthor(author); document.setDocumentInformation(info);
            document.save(path.toFile());
        }
    }

    private void writeMobi(Path path, String title, String author) throws Exception {
        Files.createDirectories(path.getParent());
        byte[] data = new byte[2048];
        putU16(data, 76, 1); putU32(data, 78, 86);
        int r0 = 86; putAscii(data, r0 + 16, "MOBI"); putU32(data, r0 + 20, 232); putU32(data, r0 + 28, 65001);
        putU32(data, r0 + 16 + 112, 0x40);
        int exth = r0 + 16 + 232; putAscii(data, exth, "EXTH");
        byte[][] records = { exthRecord(503, title), exthRecord(100, author) };
        int length = 12; for (byte[] record : records) length += record.length;
        putU32(data, exth + 4, length); putU32(data, exth + 8, records.length);
        int pos = exth + 12; for (byte[] record : records) { System.arraycopy(record, 0, data, pos, record.length); pos += record.length; }
        Files.write(path, data);
    }

    private void writeMultiBookZip(Path path) throws Exception {
        Files.createDirectories(path.getParent());
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(path))) {
            putZip(zip, "alpha/book-a.fb2", fb2String("Archive A", "Archive Author A", "", ""));
            putZip(zip, "beta/book-b.fb2", fb2String("Archive B", "Archive Author B", "Series B", "2"));
        }
    }

    private String fb2String(String title, String author, String series, String number) {
        String[] parts = author.split(" ", 2);
        String first = parts[0], last = parts.length > 1 ? parts[1] : "";
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>"
                + "<FictionBook xmlns=\"http://www.gribuser.ru/xml/fictionbook/2.0\"><description><title-info>"
                + "<author><first-name>" + first + "</first-name><last-name>" + last + "</last-name></author>"
                + "<book-title>" + title + "</book-title>"
                + (series.isBlank() ? "" : "<sequence name=\"" + series + "\" number=\"" + number + "\"/>")
                + "</title-info></description><body><section><p>x</p></section></body></FictionBook>";
    }

    private static void putZip(ZipOutputStream zip, String name, String value) throws Exception {
        zip.putNextEntry(new ZipEntry(name)); zip.write(value.getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
    }
    private static byte[] exthRecord(int type, String value) {
        byte[] text = value.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(8 + text.length).order(ByteOrder.BIG_ENDIAN);
        b.putInt(type).putInt(8 + text.length).put(text); return b.array();
    }
    private static void putAscii(byte[] data, int pos, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.US_ASCII); System.arraycopy(bytes, 0, data, pos, bytes.length);
    }
    private static void putU16(byte[] data, int pos, int value) { data[pos] = (byte) (value >>> 8); data[pos + 1] = (byte) value; }
    private static void putU32(byte[] data, int pos, int value) { ByteBuffer.wrap(data, pos, 4).order(ByteOrder.BIG_ENDIAN).putInt(value); }
    private static final class InMemoryJournal implements OrganizationJournalPort {
        private final Map<UUID, OrganizationJournalOperation> operations = new LinkedHashMap<>();
        private final Map<UUID, LinkedHashMap<UUID, OrganizationJournalItem>> items = new LinkedHashMap<>();

        @Override public void begin(OrganizationJournalOperation operation, List<OrganizationJournalItem> snapshots) {
            operations.put(operation.operationId(), operation);
            LinkedHashMap<UUID, OrganizationJournalItem> byId = new LinkedHashMap<>();
            for (OrganizationJournalItem snapshot : snapshots) byId.put(snapshot.itemId(), snapshot);
            items.put(operation.operationId(), byId);
        }

        @Override public void updateOperation(UUID operationId, OrganizationOperationState state, String summaryJson) {
            OrganizationJournalOperation current = operations.get(operationId);
            if (current == null) throw new IllegalStateException("Unknown operation " + operationId);
            operations.put(operationId, new OrganizationJournalOperation(current.operationId(), current.profileId(),
                    current.sourceRoot(), current.destinationRoot(), state, current.startedAt(),
                    state == OrganizationOperationState.COMPLETED || state == OrganizationOperationState.ROLLED_BACK
                            ? Instant.now() : current.completedAt(), summaryJson));
        }

        @Override public void updateItem(OrganizationJournalItem item) {
            items.computeIfAbsent(item.operationId(), ignored -> new LinkedHashMap<>()).put(item.itemId(), item);
        }

        @Override public List<OrganizationJournalOperation> incompleteOperations() {
            return operations.values().stream()
                    .filter(operation -> operation.state() != OrganizationOperationState.COMPLETED
                            && operation.state() != OrganizationOperationState.ROLLED_BACK)
                    .toList();
        }

        @Override public List<OrganizationJournalItem> items(UUID operationId) {
            return new ArrayList<>(items.getOrDefault(operationId, new LinkedHashMap<>()).values());
        }

        OrganizationJournalOperation operation(UUID id) { return operations.get(id); }
    }

}
