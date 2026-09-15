package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.port.out.collection.LegacyCollectionAttachPort;
import com.myhomelibcorp.application.usecase.collection.AttachHlc2CollectionUseCase;
import com.myhomelibcorp.ui.util.UiExceptionMessages;
import com.myhomelibcorp.ui.util.UiExecutor;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Consumer;

@Component
@RequiredArgsConstructor
public class CollectionAttachUiService {
    private final AttachHlc2CollectionUseCase useCase;
    private final DialogService dialogs;
    private final UiBackgroundExecutor executor;

    /** Collects user choices on the FX thread, then performs migration/SQLite I/O off the UI thread. */
    public void attach(Window owner, Consumer<LegacyCollectionAttachPort.AttachResult> onComplete) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Підключити колекцію MyHomeLib");
        fc.getExtensionFilters().addAll(
                new FileChooser.ExtensionFilter("Колекції MyHomeLib", "*.hlc2", "*.db", "*.sqlite"),
                new FileChooser.ExtensionFilter("Усі файли", "*.*"));
        File source = fc.showOpenDialog(owner);
        if (source == null) return;

        String base = source.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        Optional<String> name = dialogs.showTextInput("Підключення колекції", "Назва колекції", "Назва:", base);
        if (name.isEmpty() || name.get().isBlank()) return;

        DirectoryChooser dc = new DirectoryChooser();
        dc.setTitle("Коренева папка з книгами (Скасувати = папка біля .hlc2)");
        File parent = source.getParentFile();
        if (parent != null && parent.isDirectory()) dc.setInitialDirectory(parent);
        File root = dc.showDialog(owner);
        Path rootPath = root == null ? source.toPath().toAbsolutePath().getParent() : root.toPath();
        Path sourcePath = source.toPath();
        String collectionName = name.get();

        executor.submit(() -> useCase.execute(sourcePath, collectionName, rootPath))
                .whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
                    if (error != null) {
                        dialogs.showError("Помилка підключення", UiExceptionMessages.root(error));
                        return;
                    }
                    String mode = result.migratedLegacy()
                            ? "Оригінальну .hlc2 безпечно перенесено в сучасну sidecar-БД."
                            : "Сучасну SQLite-колекцію підключено напряму.";
                    dialogs.showInfo("Колекцію підключено", mode + "\nКниг: " + result.books()
                            + "\nАвторів: " + result.authors() + "\nЖанрів: " + result.genres());
                    if (onComplete != null) onComplete.accept(result);
                }));
    }

}
