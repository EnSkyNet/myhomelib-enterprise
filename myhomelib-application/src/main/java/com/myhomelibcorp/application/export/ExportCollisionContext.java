package com.myhomelibcorp.application.export;

import com.myhomelibcorp.domain.model.valueobject.BookId;
import java.nio.file.Path;

public record ExportCollisionContext(BookId bookId, String title, Path existingFile) { }
