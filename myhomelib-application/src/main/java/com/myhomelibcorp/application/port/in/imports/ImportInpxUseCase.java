package com.myhomelibcorp.application.port.in.imports;

import java.nio.file.Path;

/**
 * Use Case: імпорт INPX файлу.
 */
public interface ImportInpxUseCase {
    int importInpx(Path file);
}