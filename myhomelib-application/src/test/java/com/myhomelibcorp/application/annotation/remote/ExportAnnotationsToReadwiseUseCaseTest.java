package com.myhomelibcorp.application.annotation.remote;

import com.myhomelibcorp.application.annotation.export.*;
import com.myhomelibcorp.application.port.out.annotation.*;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;

class ExportAnnotationsToReadwiseUseCaseTest {
    @Test
    void batchesChangedRowsAndSkipsUnchangedFingerprints() {
        List<AnnotationExportRow> rows = new ArrayList<>();
        for (int i=0;i<205;i++) rows.add(row("a"+i, "quote-"+i));
        FakeQuery query = new FakeQuery(rows);
        FakeState state = new FakeState();
        state.values.put("readwise:a0", ExportAnnotationsToReadwiseUseCase.fingerprint(rows.get(0)));
        FakeRemote remote = new FakeRemote();
        var useCase = new ExportAnnotationsToReadwiseUseCase(query, remote, state);

        ReadwiseExportResult result = useCase.export(AnnotationExportSelection.all());

        assertThat(result.scanned()).isEqualTo(205);
        assertThat(result.unchanged()).isEqualTo(1);
        assertThat(result.exported()).isEqualTo(204);
        assertThat(result.batches()).isEqualTo(3);
        assertThat(remote.sizes).containsExactly(100,100,4);
        assertThat(state.values).hasSize(205);
    }

    @Test
    void partialRemoteAcceptanceOnlyAdvancesAcceptedCheckpoint() {
        List<AnnotationExportRow> rows = List.of(row("a1","one"), row("a2","two"));
        FakeState state = new FakeState();
        AnnotationRemoteExportPort remote = new AnnotationRemoteExportPort() {
            public RemoteAnnotationExportBatchResult exportBatch(List<RemoteAnnotationItem> items) {
                return new RemoteAnnotationExportBatchResult(Set.of("a1"));
            }
            public void testConnection() { }
        };
        var result = new ExportAnnotationsToReadwiseUseCase(new FakeQuery(rows), remote, state)
                .export(AnnotationExportSelection.all());
        assertThat(result.exported()).isEqualTo(1);
        assertThat(state.values).containsKey("readwise:a1").doesNotContainKey("readwise:a2");
    }

    @Test
    void changedAnnotationGetsNewFingerprintAndIsResent() {
        AnnotationExportRow before=row("same","old"); AnnotationExportRow after=row("same","new");
        assertThat(ExportAnnotationsToReadwiseUseCase.fingerprint(before))
                .isNotEqualTo(ExportAnnotationsToReadwiseUseCase.fingerprint(after));
    }

    private static AnnotationExportRow row(String id,String quote) {
        return new AnnotationExportRow(id,"book-1","Book","Author","Series","uk","book.fb2","",
                "HIGHLIGHT","yellow","c1","Chapter",quote,"note",List.of("tag"),0.25,
                Instant.parse("2026-10-01T10:00:00Z"), Instant.parse("2026-10-01T10:05:00Z"));
    }
    private static final class FakeQuery implements AnnotationExportQueryPort {
        final List<AnnotationExportRow> rows; FakeQuery(List<AnnotationExportRow> rows){this.rows=rows;}
        public AnnotationExportPage query(AnnotationExportSelection selection,int offset,int limit){
            int end=Math.min(rows.size(),offset+limit); List<AnnotationExportRow> page=offset>=rows.size()?List.of():rows.subList(offset,end);
            return new AnnotationExportPage(page,offset,limit,end<rows.size());
        }
    }
    private static final class FakeState implements AnnotationRemoteExportStatePort {
        final Map<String,String> values=new LinkedHashMap<>();
        public Optional<String> exportedFingerprint(String remote,String id){return Optional.ofNullable(values.get(remote+":"+id));}
        public void markExported(String remote,Map<String,String> fingerprints){fingerprints.forEach((id,fp)->values.put(remote+":"+id,fp));}
    }
    private static final class FakeRemote implements AnnotationRemoteExportPort {
        final List<Integer> sizes=new ArrayList<>();
        public RemoteAnnotationExportBatchResult exportBatch(List<RemoteAnnotationItem> items){sizes.add(items.size()); return new RemoteAnnotationExportBatchResult(items.stream().map(RemoteAnnotationItem::annotationId).collect(java.util.stream.Collectors.toSet()));}
        public void testConnection() { }
    }
}
