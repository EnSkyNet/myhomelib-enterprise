package com.myhomelibcorp.ui.details.model;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.dto.GroupDto;
import com.myhomelibcorp.application.usecase.book.ResolvedBookContent;
import com.myhomelibcorp.reader.inspection.DocumentInspection;
import com.myhomelibcorp.reader.inspection.DocumentInspectionSession;

import java.io.InputStream;
import java.util.Optional;

public final class RichBookDetailsSession implements AutoCloseable {
    private final BookDto book;
    private final DocumentInspection inspection;
    private final java.util.List<GroupDto> groups;
    private final DocumentInspectionSession inspectionSession;
    private final ResolvedBookContent source;

    public RichBookDetailsSession(
            BookDto book,
            DocumentInspection inspection,
            java.util.List<GroupDto> groups,
            DocumentInspectionSession inspectionSession,
            ResolvedBookContent source
    ) {
        this.book = book;
        this.inspection = inspection;
        this.groups = groups == null ? java.util.List.of() : java.util.List.copyOf(groups);
        this.inspectionSession = inspectionSession;
        this.source = source;
    }

    public BookDto book() { return book; }
    public DocumentInspection inspection() { return inspection; }
    public java.util.List<GroupDto> groups() { return groups; }

    public Optional<InputStream> openImage(String id) {
        return inspectionSession == null ? Optional.empty() : inspectionSession.openImage(id);
    }

    @Override
    public void close() {
        if (inspectionSession != null) inspectionSession.close();
        if (source != null) source.close();
    }
}
