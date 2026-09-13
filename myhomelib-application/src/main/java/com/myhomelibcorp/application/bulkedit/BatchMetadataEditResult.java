package com.myhomelibcorp.application.bulkedit;

public record BatchMetadataEditResult(String operationId, int selectedCount, int changedCount, boolean searchIndexScheduled) {
}
