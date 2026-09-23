package com.myhomelibcorp.organizer.analysis;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

public record OrganizationAnalysis(Path sourceRoot, Path destinationRoot, Instant createdAt,
                                   List<OrganizerAnalysisItem> items, List<String> warnings) {
    public OrganizationAnalysis { createdAt=createdAt==null?Instant.now():createdAt;items=List.copyOf(items==null?List.of():items);warnings=List.copyOf(warnings==null?List.of():warnings); }
}
