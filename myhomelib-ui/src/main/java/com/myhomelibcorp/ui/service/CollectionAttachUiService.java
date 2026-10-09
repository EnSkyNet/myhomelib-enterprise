package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.port.out.collection.LegacyCollectionAttachPort;
import com.myhomelibcorp.application.usecase.collection.AttachHlc2CollectionUseCase;
import com.myhomelibcorp.ui.util.UiExceptionMessages;
import com.myhomelibcorp.ui.util.UiExecutor;
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
    private final FileChooserService fileChooserService;

    /** Collects user choices on the FX thread, then performs migration/SQLite I/O off the UI thread. */
    public void attach(Window owner, Consumer<LegacyCollectionAttachPort.AttachResult> onComplete) {
        File source = fileChooserService.chooseFile(owner, UiText.text("ui.java.collection_attach_ui_service.09fbdd11a8"),
                java.util.List.of(
                        new FileChooser.ExtensionFilter(UiText.text("ui.java.collection_attach_ui_service.8bcc8b7872"), "*.hlc2", "*.db", "*.sqlite"),
                        new FileChooser.ExtensionFilter(UiText.text("ui.java.collection_attach_ui_service.3efc61c5fd"), "*.*")));
        if (source == null) return;

        String base = source.getName();
        int dot = base.lastIndexOf('.');
        if (dot > 0) base = base.substring(0, dot);
        Optional<String> name = dialogs.showTextInput(UiText.text("ui.main.collection.attach_title"), UiText.text("ui.java.collection_attach_ui_service.4073bfd8ec"), UiText.text("common.name.label"), base);
        if (name.isEmpty() || name.get().isBlank()) return;

        File parent = source.getParentFile();
        File root = fileChooserService.chooseDirectory(owner, UiText.text("ui.java.collection_attach_ui_service.c7b4c70a12"), parent);
        Path rootPath = root == null ? source.toPath().toAbsolutePath().getParent() : root.toPath();
        Path sourcePath = source.toPath();
        String collectionName = name.get();

        executor.submit(() -> useCase.execute(sourcePath, collectionName, rootPath))
                .whenComplete((result, error) -> UiExecutor.runOnUiThread(() -> {
                    if (error != null) {
                        dialogs.showError(UiText.text("ui.java.collection_attach_ui_service.a44d45c327"), UiExceptionMessages.root(error));
                        return;
                    }
                    String mode = result.migratedLegacy()
                            ? UiText.text("ui.java.collection_attach_ui_service.033da8b408")
                            : UiText.text("ui.java.collection_attach_ui_service.4c4ae5bcab");
                    dialogs.showInfo(UiText.text("ui.java.collection_attach_ui_service.d497f347a6"), mode + UiText.text("ui.java.collection_attach_ui_service.e50977557a") + result.books()
                            + UiText.text("ui.java.collection_attach_ui_service.74e75bdb57") + result.authors() + UiText.text("ui.java.collection_attach_ui_service.99730559bb") + result.genres());
                    if (onComplete != null) onComplete.accept(result);
                }));
    }

}
