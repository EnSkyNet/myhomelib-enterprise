package com.myhomelibcorp.organizer.analysis;

import com.myhomelibcorp.organizer.detect.DetectedBookFormat;
import com.myhomelibcorp.organizer.duplicate.DuplicateDecision;
import com.myhomelibcorp.organizer.fingerprint.BookFingerprint;
import com.myhomelibcorp.organizer.metadata.ExtractedBookMetadata;
import com.myhomelibcorp.organizer.metadata.NormalizedBookIdentity;
import com.myhomelibcorp.organizer.policy.CanonicalFormatDecision;

import java.nio.file.Path;
import java.util.List;

/** One logical book candidate. sourceEntry is non-blank only for a book contained in a multi-book archive. */
public record OrganizerAnalysisItem(Path sourcePath, String sourceEntry, DetectedBookFormat detectedFormat,
                                    ExtractedBookMetadata extractedMetadata, NormalizedBookIdentity identity,
                                    BookFingerprint fingerprint, CanonicalFormatDecision formatDecision,
                                    Path proposedRelativePath, DuplicateDecision duplicate,
                                    List<String> warnings) {
    public OrganizerAnalysisItem {
        sourceEntry = sourceEntry == null ? "" : sourceEntry;
        duplicate=duplicate==null?DuplicateDecision.none():duplicate;
        warnings=List.copyOf(warnings==null?List.of():warnings);
    }

    /** Compatibility constructor for ordinary non-archive sources. */
    public OrganizerAnalysisItem(Path sourcePath, DetectedBookFormat detectedFormat,
                                 ExtractedBookMetadata extractedMetadata, NormalizedBookIdentity identity,
                                 BookFingerprint fingerprint, CanonicalFormatDecision formatDecision,
                                 Path proposedRelativePath, DuplicateDecision duplicate, List<String> warnings) {
        this(sourcePath, "", detectedFormat, extractedMetadata, identity, fingerprint, formatDecision,
                proposedRelativePath, duplicate, warnings);
    }

    public boolean archiveEntry() { return !sourceEntry.isBlank(); }

    public OrganizerAnalysisItem withDuplicate(DuplicateDecision decision){
        return new OrganizerAnalysisItem(sourcePath,sourceEntry,detectedFormat,extractedMetadata,identity,fingerprint,formatDecision,proposedRelativePath,decision,warnings);
    }
}
