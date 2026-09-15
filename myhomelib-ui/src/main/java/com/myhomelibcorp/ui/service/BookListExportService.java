package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.ui.util.UiExceptionMessages;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.springframework.stereotype.Service;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;
import java.util.Locale;

@Service
public class BookListExportService {
    private final ApplicationState state;
    private final DialogService dialogs;
    private final UiBackgroundExecutor executor;

    public BookListExportService(ApplicationState state, DialogService dialogs, UiBackgroundExecutor executor) {
        this.state = state;
        this.dialogs = dialogs;
        this.executor = executor;
    }

    public void export(Window owner, String format) {
        List<ExportRow> books = state.getBookTable().getBooks().stream()
                .map(BookListExportService::snapshot)
                .toList();
        if (books.isEmpty()) {
            dialogs.showWarning("Порожній список", "Немає книг для експорту.");
            return;
        }
        String f = format.toLowerCase(Locale.ROOT);
        FileChooser fc = new FileChooser();
        fc.setTitle("Експорт поточного списку");
        fc.setInitialFileName("books." + f);
        fc.getExtensionFilters().add(new FileChooser.ExtensionFilter(f.toUpperCase(Locale.ROOT), "*." + f));
        File file = fc.showSaveDialog(owner);
        if (file == null) return;

        executor.submit(() -> {
            String data = switch (f) {
                case "html" -> html(books);
                case "rtf" -> rtf(books);
                default -> txt(books);
            };
            Files.writeString(file.toPath(), data, StandardCharsets.UTF_8);
            return books.size();
        }).whenComplete((count, error) -> UiExecutor.runOnUiThread(() -> {
            if (error != null) dialogs.showError("Помилка експорту", UiExceptionMessages.root(error));
            else dialogs.showInfo("Експорт завершено", count + " книг -> " + file);
        }));
    }

    private String txt(List<ExportRow> books) {
        StringBuilder s = new StringBuilder();
        int i = 1;
        for (var x : books) {
            s.append(i++).append(". ").append(x.authors()).append(" — ").append(x.title())
                    .append(x.series() == null || x.series().isBlank() ? "" : " [" + x.series() + "]")
                    .append('\n');
        }
        return s.toString();
    }

    private String html(List<ExportRow> books) {
        StringBuilder s = new StringBuilder("<!doctype html><meta charset=\"utf-8\"><title>MyHomeLib</title>"
                + "<h1>MyHomeLib</h1><table border=\"1\"><tr><th>#</th><th>Автор</th><th>Назва</th>"
                + "<th>Серія</th><th>Жанри</th><th>Рейтинг</th></tr>");
        int i = 1;
        for (var x : books) {
            s.append("<tr><td>").append(i++).append("</td><td>").append(esc(x.authors()))
                    .append("</td><td>").append(esc(x.title())).append("</td><td>").append(esc(x.series()))
                    .append("</td><td>").append(esc(x.genres())).append("</td><td>").append(x.rate())
                    .append("</td></tr>");
        }
        return s.append("</table>").toString();
    }

    private String rtf(List<ExportRow> books) {
        StringBuilder s = new StringBuilder("{\\rtf1\\ansi\\deff0\\uc1 MyHomeLib\\par ");
        int i = 1;
        for (var x : books) {
            s.append(i++).append(". ").append(rtfEsc(x.authors())).append(" - ")
                    .append(rtfEsc(x.title())).append("\\par ");
        }
        return s.append('}').toString();
    }

    private String esc(String x) {
        if (x == null) return "";
        return x.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String rtfEsc(String x) {
        if (x == null) return "";
        StringBuilder b = new StringBuilder();
        for (char c : x.toCharArray()) {
            if (c == '\\' || c == '{' || c == '}') b.append('\\').append(c);
            else if (c > 127) b.append("\\u").append((int) c).append('?');
            else b.append(c);
        }
        return b.toString();
    }

    private static ExportRow snapshot(BookViewModel book) {
        return new ExportRow(book.getAuthorsText(), book.getTitle(), book.getSeries(), book.getGenresText(), book.getRate());
    }

    private record ExportRow(String authors, String title, String series, String genres, int rate) { }

}
