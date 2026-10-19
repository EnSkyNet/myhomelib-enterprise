package com.myhomelibcorp.ui.organizer;

import com.myhomelibcorp.organizer.analysis.AuthorFolderOrderPreview;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import static org.assertj.core.api.Assertions.assertThat;

class AuthorFolderBatchCsvTest {
    @Test
    void localizesAndEscapesExcelFormulasSeparatorsAndQuotes() {
        var csv = AuthorFolderBatchCsv.render(List.of(
                        new AuthorFolderOrderPreview.Result("=1+2", "Кузнецов, \"Пётр\"", 2,
                                AuthorFolderOrderPreview.Status.PROPOSAL),
                        new AuthorFolderOrderPreview.Result("Иванов; Иван", "", 0,
                                AuthorFolderOrderPreview.Status.NO_BOOKS)),
                List.of("Поточна", "Нова", "Книг", "Статус"),
                Map.of(AuthorFolderOrderPreview.Status.PROPOSAL, "Пропозиція",
                        AuthorFolderOrderPreview.Status.NO_BOOKS, "Немає книг"));
        assertThat(csv).startsWith("\uFEFF\"Поточна\";");
        assertThat(csv).contains("\"'=1+2\";");
        assertThat(csv).contains("\"Кузнецов, \"\"Пётр\"\"\";");
        assertThat(csv).contains("\"Иванов; Иван\";");
        assertThat(csv).contains("Пропозиція");
    }
}
