package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.shared.util.AtomicFileSupport;
import com.myhomelibcorp.ui.util.UiExceptionMessages;
import com.myhomelibcorp.ui.util.UiExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.springframework.stereotype.Service;

import java.io.File;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

@Service
public class BookListExportService {
    private final ApplicationState state;
    private final DialogService dialogs;
    private final UiBackgroundExecutor executor;
    private final FileChooserService fileChooserService;

    public BookListExportService(ApplicationState state, DialogService dialogs, UiBackgroundExecutor executor,
                                 FileChooserService fileChooserService) {
        this.state = state;
        this.dialogs = dialogs;
        this.executor = executor;
        this.fileChooserService = fileChooserService;
    }

    public void export(Window owner, String format) {
        List<ExportRow> books = state.getBookTable().getBooks().stream()
                .map(BookListExportService::snapshot)
                .toList();
        if (books.isEmpty()) {
            dialogs.showWarning(UiText.text("ui.java.book_list_export_service.0fdd0f46e8"), UiText.text("ui.java.book_list_export_service.b48f0b18f3"));
            return;
        }
        String f = format.toLowerCase(Locale.ROOT);
        File file = fileChooserService.chooseFileToSave(owner, UiText.text("ui.main.fxml.export_current_list"),
                "books." + f, List.of(new FileChooser.ExtensionFilter(f.toUpperCase(Locale.ROOT), "*." + f)));
        if (file == null) return;

        executor.submit(() -> {
            AtomicFileSupport.writeWithWriterReplacing(
                    file.toPath(), StandardCharsets.UTF_8,
                    writer -> writeRows(f, books, writer));
            return books.size();
        }).whenComplete((count, error) -> UiExecutor.runOnUiThread(() -> {
            if (error != null) dialogs.showError(UiText.text("ui.java.book_list_export_service.68c7ce9fff"), UiExceptionMessages.root(error));
            else dialogs.showInfo(UiText.text("ui.java.book_list_export_service.61676a8b43"), count + UiText.text("ui.java.book_list_export_service.f363a5a5f6") + file);
        }));
    }

    /** Append rows incrementally: no output-sized StringBuilder is retained in heap. */
    void writeRows(String format, List<ExportRow> books, Writer out) throws IOException {
        switch (format) {
            case "html" -> writeHtml(books, out);
            case "rtf" -> writeRtf(books, out);
            default -> writeTxt(books, out);
        }
    }

    private void writeTxt(List<ExportRow> books, Writer out) throws IOException {
        int i = 1;
        for (var x : books) {
            out.append(Integer.toString(i++)).append(". ").append(x.authors()).append(" — ").append(x.title())
                    .append(x.series() == null || x.series().isBlank() ? "" : " [" + x.series() + "]")
                    .append('\n');
        }
    }

    private void writeHtml(List<ExportRow> books, Writer out) throws IOException {
        out.append("<!doctype html><meta charset=\"utf-8\"><title>MyHomeLib</title>")
                .append(UiText.text("ui.java.book_list_export_service.50d4c8ee76"))
                .append(UiText.text("ui.java.book_list_export_service.55be5d0555"));
        int i = 1;
        for (var x : books) {
            out.append("<tr><td>").append(Integer.toString(i++)).append("</td><td>").append(esc(x.authors()))
                    .append("</td><td>").append(esc(x.title())).append("</td><td>").append(esc(x.series()))
                    .append("</td><td>").append(esc(x.genres())).append("</td><td>")
                    .append(Integer.toString(x.rate())).append("</td></tr>");
        }
        out.append("</table>");
    }

    private void writeRtf(List<ExportRow> books, Writer out) throws IOException {
        out.append("{\\rtf1\\ansi\\deff0\\uc1 MyHomeLib\\par ");
        int i = 1;
        for (var x : books) {
            out.append(Integer.toString(i++)).append(". ").append(rtfEsc(x.authors())).append(" - ")
                    .append(rtfEsc(x.title())).append("\\par ");
        }
        out.append('}');
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

    record ExportRow(String authors, String title, String series, String genres, int rate) { }

}
