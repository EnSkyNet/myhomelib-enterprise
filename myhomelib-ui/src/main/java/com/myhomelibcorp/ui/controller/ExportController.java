package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.ui.service.DialogLocalization;
import com.myhomelibcorp.ui.util.UiExceptionSupport;

import com.myhomelibcorp.ui.service.UiText;
import com.myhomelibcorp.application.action.BookActionProfile;
import com.myhomelibcorp.application.action.BookActionProfileService;
import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.dto.InpxExportRequest;
import com.myhomelibcorp.application.export.DeviceProfileService;
import com.myhomelibcorp.application.export.DeviceTargetProfile;
import com.myhomelibcorp.application.export.ExportCollisionContext;
import com.myhomelibcorp.application.export.ExportCollisionDecision;
import com.myhomelibcorp.application.export.ExportHistoryEntry;
import com.myhomelibcorp.application.export.ExportHistoryService;
import com.myhomelibcorp.application.export.ExportLastStateService;
import com.myhomelibcorp.application.export.ExportProfile;
import com.myhomelibcorp.application.export.ExportProfileService;
import com.myhomelibcorp.application.operation.LibraryOperationType;
import com.myhomelibcorp.application.usecase.book.LoadBookByIdUseCase;
import com.myhomelibcorp.application.usecase.export.ExportToDeviceUseCase;
import com.myhomelibcorp.application.usecase.export.ExportToInpxUseCase;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import com.myhomelibcorp.ui.mapper.BookViewModelMapper;
import com.myhomelibcorp.ui.service.BookSelectionService;
import com.myhomelibcorp.ui.service.BookDownloadCoordinator;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FxmlLoaderFactory;
import com.myhomelibcorp.ui.service.LocalizationService;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import com.myhomelibcorp.ui.viewmodel.BookViewModel;
import javafx.animation.PauseTransition;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.BorderPane;
import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Duration;
import javafx.util.StringConverter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.File;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@Component
@RequiredArgsConstructor
@Slf4j
public class ExportController {

    private final ExportToDeviceUseCase exportToDeviceUseCase;
    private final ExportToInpxUseCase exportToInpxUseCase;
    private final ExportProfileService exportProfileService;
    private final DeviceProfileService deviceProfileService;
    private final ExportLastStateService exportLastStateService;
    private final ExportHistoryService exportHistoryService;
    private final BookActionProfileService bookActionProfileService;
    private final ApplicationState appState;
    private final BookSelectionService bookSelectionService;
    private final BookDownloadCoordinator bookDownloadCoordinator;
    private final LoadBookByIdUseCase loadBookByIdUseCase;
    private final BookViewModelMapper bookViewModelMapper;
    private final UiBackgroundExecutor executor;
    private final DialogService dialogService;
    private final FxmlLoaderFactory fxmlLoaderFactory;
    private final LocalizationService i18n;

    @FXML private ComboBox<ExportProfile> profileComboBox;
    @FXML private ComboBox<DeviceTargetProfile> deviceProfileComboBox;
    @FXML private ComboBox<ExportRequest.ExportFormat> formatComboBox;
    @FXML private TextField destinationField;
    @FXML private TextField fileNameTemplateField;
    @FXML private TextField subfolderTemplateField;
    @FXML private ComboBox<ExportRequest.CollisionPolicy> collisionPolicyComboBox;
    @FXML private ComboBox<ActionChoice> postActionComboBox;
    @FXML private CheckBox extractOnlyCheckBox;
    @FXML private Label selectedBooksLabel;
    @FXML private Label calibreStatusLabel;
    @FXML private Button exportButton;
    @FXML private Button cancelButton;
    @FXML private Button updateProfileButton;
    @FXML private Button deleteProfileButton;

    private List<BookId> selectedBookIds = List.of();
    private Stage stage;
    private final AtomicBoolean exportCancelFlag = new AtomicBoolean(false);
    private volatile boolean exportRunning;
    private boolean applyingProfile;
    private Runnable successfulExportCallback = () -> { };

    @FXML
    public void initialize() {
        Set<ExportRequest.ExportFormat> supported = exportToDeviceUseCase.supportedFormats();
        formatComboBox.getItems().setAll(java.util.Arrays.stream(ExportRequest.ExportFormat.values())
                .filter(supported::contains).toList());
        if (!formatComboBox.getItems().isEmpty()) formatComboBox.setValue(formatComboBox.getItems().getFirst());
        boolean calibreTargetsAvailable = supported.stream().anyMatch(format -> switch (format) {
            case AZW3, DOCX, HTMLZ, KEPUB, LIT, PDB, PMLZ, RB, RTF, SNB, TCR, TXTZ, ZIP -> true;
            default -> false;
        });
        calibreStatusLabel.setVisible(!calibreTargetsAvailable);
        calibreStatusLabel.setManaged(!calibreTargetsAvailable);

        collisionPolicyComboBox.getItems().setAll(
                ExportRequest.CollisionPolicy.RENAME,
                ExportRequest.CollisionPolicy.OVERWRITE,
                ExportRequest.CollisionPolicy.SKIP,
                ExportRequest.CollisionPolicy.ASK);
        collisionPolicyComboBox.setValue(ExportRequest.CollisionPolicy.RENAME);
        collisionPolicyComboBox.setConverter(new StringConverter<>() {
            @Override public String toString(ExportRequest.CollisionPolicy p) {
                if (p == null) return "";
                return switch (p) {
                    case RENAME -> i18n.text("ui.export.collision.rename");
                    case OVERWRITE -> i18n.text("ui.export.collision.overwrite");
                    case SKIP -> i18n.text("ui.export.collision.skip");
                    case ASK -> i18n.text("ui.export.collision.ask");
                };
            }
            @Override public ExportRequest.CollisionPolicy fromString(String value) {
                if (value == null) return collisionPolicyComboBox.getValue();
                String normalized = value.trim();
                for (ExportRequest.CollisionPolicy policy : ExportRequest.CollisionPolicy.values()) {
                    if (toString(policy).equalsIgnoreCase(normalized) || policy.name().equalsIgnoreCase(normalized)) return policy;
                }
                return collisionPolicyComboBox.getValue();
            }
        });

        deviceProfileComboBox.getItems().setAll(deviceProfileService.loadProfiles());
        deviceProfileComboBox.setConverter(new StringConverter<>() {
            @Override public String toString(DeviceTargetProfile p) { return deviceProfileName(p); }
            @Override public DeviceTargetProfile fromString(String value) { return deviceProfileComboBox.getValue(); }
        });
        deviceProfileComboBox.setValue(deviceProfileService.genericFolder());
        deviceProfileComboBox.valueProperty().addListener((obs, old, value) -> {
            if (!applyingProfile) applyDevicePreferredFormat(value);
        });

        profileComboBox.setConverter(new StringConverter<>() {
            @Override public String toString(ExportProfile p) { return exportProfileName(p); }
            @Override public ExportProfile fromString(String value) {
                if (value == null) return profileComboBox.getValue();
                return profileComboBox.getItems().stream()
                        .filter(profile -> profile.name().equalsIgnoreCase(value.trim()))
                        .findFirst().orElse(profileComboBox.getValue());
            }
        });
        profileComboBox.valueProperty().addListener((obs, old, value) -> {
            if (applyingProfile) return;
            exportLastStateService.rememberProfile(value == null ? "" : value.id());
            applyProfile(value);
        });
        ExportLastStateService.State lastState = exportLastStateService.load();
        loadPostActions(lastState.hasPostAction() ? lastState.postActionId() : "");
        loadProfiles(lastState.profileId());
        restoreLastExportState(lastState);

        selectedBooksLabel.setText(i18n.text("ui.export.selection.none"));
        exportButton.setDisable(true);
        updateProfileButtons();
    }

    private String exportProfileName(ExportProfile profile) {
        if (profile == null) return "";
        return "default-export".equals(profile.id()) ? i18n.text("ui.export.profile.default") : profile.name();
    }

    private String deviceProfileName(DeviceTargetProfile profile) {
        if (profile == null) return "";
        return switch (profile.id()) {
            case DeviceProfileService.GENERIC_FOLDER_ID -> i18n.text("ui.export.device.generic_folder");
            case DeviceProfileService.KINDLE_ID -> i18n.text("ui.export.device.kindle_usb");
            case DeviceProfileService.KOBO_ID -> i18n.text("ui.export.device.kobo");
            case DeviceProfileService.POCKETBOOK_ID -> i18n.text("ui.export.device.pocketbook");
            case DeviceProfileService.ANDROID_ID -> i18n.text("ui.export.device.android_storage");
            default -> profile.displayName();
        };
    }

    public void setSelectedBooks(List<BookViewModel> selectedBooks) {
        if (selectedBooks == null || selectedBooks.isEmpty()) {
            selectedBookIds = List.of();
            selectedBooksLabel.setText(i18n.text("ui.export.selection.none"));
            exportButton.setDisable(true);
            return;
        }

        List<BookViewModel> validBooks = selectedBooks.stream()
                .filter(b -> b.getId() != null && !b.getId().isBlank()).toList();
        selectedBookIds = validBooks.stream().map(b -> BookId.fromString(b.getId())).collect(Collectors.toList());
        Set<ExportRequest.ExportFormat> common = exportToDeviceUseCase.supportedFormatsForBooks(selectedBookIds);
        ExportRequest.ExportFormat preferred = formatComboBox.getValue();
        formatComboBox.getItems().setAll(java.util.Arrays.stream(ExportRequest.ExportFormat.values())
                .filter(common::contains).toList());
        selectedBooksLabel.setText(i18n.format("ui.export.selection.count", validBooks.size()));
        if (validBooks.isEmpty() || common.isEmpty()) {
            formatComboBox.setValue(null);
            exportButton.setDisable(true);
            if (!validBooks.isEmpty()) selectedBooksLabel.setText(
                    i18n.format("ui.export.selection.no_common_format", validBooks.size()));
        } else {
            ExportProfile profile = profileComboBox.getValue();
            DeviceTargetProfile device = deviceProfileComboBox.getValue();
            ExportRequest.ExportFormat devicePreferred = firstSupportedDeviceFormat(device, common);
            if (profile != null && common.contains(profile.format())) formatComboBox.setValue(profile.format());
            else if (devicePreferred != null) formatComboBox.setValue(devicePreferred);
            else if (preferred != null && common.contains(preferred)) formatComboBox.setValue(preferred);
            else formatComboBox.setValue(formatComboBox.getItems().getFirst());
            exportButton.setDisable(false);
        }
    }

    public void setStage(Stage stage) {
        this.stage = stage;
        if (stage != null) {
            stage.setOnCloseRequest(event -> {
                if (exportRunning) {
                    exportCancelFlag.set(true);
                    event.consume();
                }
            });
        }
    }

    // ==================== ЕКСПОРТ НА ПРИСТРІЙ ====================

    public void handleExport(BorderPane mainPane) {
        handleExport(mainPane == null || mainPane.getScene() == null ? null : mainPane.getScene().getWindow());
    }

    /** Device export always uses the shared checkbox selection; row cursor is never a fallback. */
    public void handleExport(Window owner) {
        handleExport(owner, null);
    }

    /**
     * Device export with a workspace-local refresh callback. The callback runs only after a
     * non-cancelled export with no failures/errors, after the exported checkbox selection is cleared.
     */
    public void handleExport(Window owner, Runnable onSuccessfulExport) {
        List<BookId> selectedIds = bookSelectionService.snapshot();
        if (selectedIds.isEmpty()) {
            dialogService.showWarning(UiText.text("ui.java.export_controller.ac1e0c3aad"),
                    UiText.text("ui.java.export_controller.ea4e147415"));
            return;
        }
        executor.submitCollectionScoped(LibraryOperationType.QUERY, () -> selectedIds.stream()
                        .map(loadBookByIdUseCase::execute)
                        .flatMap(java.util.Optional::stream)
                        .toList())
                .thenAccept(books -> Platform.runLater(() -> {
                    List<BookViewModel> rows = books.stream().map(bookViewModelMapper::toViewModel).toList();
                    if (rows.isEmpty()) {
                        dialogService.showWarning(UiText.text("ui.fxml.integrity_check.export"), UiText.text("ui.java.export_controller.adab3b50f7"));
                        return;
                    }
                    showExportDialog(owner, rows, onSuccessfulExport);
                }))
                .exceptionally(error -> {
                    Platform.runLater(() -> dialogService.showError(UiText.text("ui.fxml.integrity_check.export"),
                            UiText.text("ui.java.export_controller.b689e81f73") + UiExceptionSupport.message(error)));
                    return null;
                });
    }

    public void showExportDialog(Window owner, List<BookViewModel> selectedBooks) {
        showExportDialog(owner, selectedBooks, null);
    }

    public void showExportDialog(Window owner, List<BookViewModel> selectedBooks, Runnable onSuccessfulExport) {
        if (selectedBooks == null || selectedBooks.isEmpty()) {
            dialogService.showWarning(UiText.text("ui.java.export_controller.ac1e0c3aad"), UiText.text("ui.java.export_controller.c948809806"));
            return;
        }
        try {
            FXMLLoader loader = new FXMLLoader(getClass().getResource("/view/export-dialog.fxml"));
            fxmlLoaderFactory.configureControllerFactory(loader);
            Parent root = loader.load();
            i18n.apply(root);
            ExportController controller = loader.getController();
            controller.setSelectedBooks(selectedBooks);
            controller.successfulExportCallback = onSuccessfulExport == null ? () -> { } : onSuccessfulExport;
            Stage dialogStage = new Stage();
            dialogStage.setTitle(UiText.text("ui.java.export_controller.6ab833c96b") + selectedBooks.size() + UiText.text("ui.java.export_controller.de7370facb"));
            dialogStage.setScene(new Scene(root, 700, 620));
            dialogStage.initModality(Modality.WINDOW_MODAL);
            if (owner != null) dialogStage.initOwner(owner);
            controller.setStage(dialogStage);
            try {
                dialogStage.showAndWait();
            } finally {
                controller.successfulExportCallback = () -> { };
            }
        } catch (Exception e) {
            log.error("Помилка відкриття діалогу експорту", e);
            dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.export_controller.acb44428f0") + UiExceptionSupport.message(e));
        }
    }

    // ==================== ЕКСПОРТ В INPX ====================

    public void handleExportInpx(BorderPane mainPane, Runnable onComplete) {
        List<BookId> selectedIds = bookSelectionService.snapshot();
        List<BookId> bookIds;
        if (!selectedIds.isEmpty()) {
            bookIds = selectedIds;
        } else {
            if (!dialogService.showConfirmation(UiText.text("ui.java.export_controller.87f3140a76"), UiText.text("ui.java.export_controller.3d485a12d9"), UiText.text("ui.java.export_controller.5ee33b842a"))) return;
            bookIds = null;
        }

        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(UiText.text("ui.java.export_controller.6f276c407f"));
        fileChooser.getExtensionFilters().add(new FileChooser.ExtensionFilter(UiText.text("ui.import.filter.inpx_files"), "*.inpx"));
        File file = fileChooser.showSaveDialog(mainPane.getScene().getWindow());
        if (file == null) return;

        var currentCollection = appState.getCurrentLibraryCollection();
        String collectionName = currentCollection != null ? currentCollection.getName() : UiText.text("ui.java.export_controller.b61bfa3aaa");
        InpxExportRequest request = InpxExportRequest.builder()
                .collectionId(appState.getCurrentLibraryCollectionId())
                .bookIds(bookIds).outputFile(file.toPath()).collectionName(collectionName)
                .collectionVersion(LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd")))
                .collectionType(currentCollection == null ? 0 : currentCollection.getType())
                .collectionNotes(currentCollection == null ? null : currentCollection.getNotes())
                .collectionUrl(currentCollection == null ? null : currentCollection.getUrl())
                .connectionScript(currentCollection == null ? null : currentCollection.getConnectionScript())
                .build();

        dialogService.showInfo(UiText.text("ui.fxml.integrity_check.export"), UiText.text("ui.java.export_controller.6af29821ae"));
        executor.submitCollectionScoped(LibraryOperationType.EXPORT, () -> exportToInpxUseCase.execute(request))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        Throwable cause = com.myhomelibcorp.ui.util.UiExceptionSupport.unwrapAsync(error);
                        log.error("Помилка експорту в INPX", cause);
                        dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.export_controller.e39d902e86")
                                + UiExceptionSupport.message(cause));
                    } else if (result.failed() == 0) {
                        dialogService.showInfo(UiText.text("common.success"), UiText.text("ui.java.export_controller.bb195594af") + result.exported() + UiText.text("ui.java.export_controller.cbea6aba80"));
                    } else {
                        String message = result.errorKey() == null
                                ? i18n.text("common.error.operation_failed")
                                : i18n.format(result.errorKey(), result.errorArguments().toArray());
                        if (!result.technicalDetail().isBlank()) {
                            log.error("INPX export failed: {}", result.technicalDetail());
                        }
                        dialogService.showError(UiText.text("common.error"),
                                UiText.text("ui.java.export_controller.e39d902e86") + message);
                    }
                    if (onComplete != null) onComplete.run();
                }));
    }

    // ==================== STAGE 16 PROFILES / HISTORY ====================

    @FXML private void onSaveProfileAs() {
        if (formatComboBox.getValue() == null) { dialogService.showWarning(UiText.text("ui.java.export_controller.4a34db696f"), UiText.text("ui.java.export_controller.7077fbd48d")); return; }
        TextInputDialog dialog = new TextInputDialog(profileComboBox.getValue() == null ? UiText.text("ui.java.export_controller.327a0c3af7") : profileComboBox.getValue().name());
        DialogLocalization.prepare(dialog);
        dialog.setTitle(UiText.text("ui.java.export_controller.82ceb61bf4"));
        dialog.setHeaderText(UiText.text("ui.java.export_controller.81a8a05f9c"));
        if (stage != null) dialog.initOwner(stage);
        dialog.showAndWait().map(String::trim).filter(s -> !s.isBlank()).ifPresent(name -> {
            ExportProfile created = exportProfileService.newProfile(name);
            ExportProfile saved = snapshotProfile(created.id(), name);
            exportProfileService.save(saved);
            loadProfiles(saved.id());
        });
    }

    @FXML private void onUpdateProfile() {
        ExportProfile current = profileComboBox.getValue();
        if (current == null) return;
        if (formatComboBox.getValue() == null) { dialogService.showWarning(UiText.text("ui.java.export_controller.4a34db696f"), UiText.text("ui.java.export_controller.7077fbd48d")); return; }
        ExportProfile saved = snapshotProfile(current.id(), current.name());
        exportProfileService.save(saved);
        loadProfiles(saved.id());
    }

    @FXML private void onDeleteProfile() {
        ExportProfile current = profileComboBox.getValue();
        if (current == null) return;
        if (!dialogService.showConfirmation(UiText.text("ui.java.export_controller.ed8e7a60ce"), current.name(), UiText.text("ui.java.export_controller.7be143e5b9"))) return;
        exportProfileService.delete(current.id());
        loadProfiles(null);
    }

    @FXML private void onShowHistory() {
        List<ExportHistoryEntry> history = exportHistoryService.loadRecent(30);
        StringBuilder text = new StringBuilder();
        DateTimeFormatter fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());
        if (history.isEmpty()) text.append(UiText.text("ui.java.export_controller.19d689292f"));
        for (ExportHistoryEntry e : history) {
            text.append(fmt.format(e.completedAt())).append("  ")
                    .append(e.profileName().isBlank() ? UiText.text("ui.export.history.ad_hoc") : e.profileName()).append("  ")
                    .append(e.format() == null ? "?" : e.format()).append('\n')
                    .append(UiText.text("ui.export.history.requested")).append(e.requested())
                    .append(UiText.text("ui.export.history.exported")).append(e.exported())
                    .append(UiText.text("ui.export.history.skipped")).append(e.skipped())
                    .append(UiText.text("ui.export.history.failed")).append(e.failed())
                    .append(e.cancelled() ? UiText.text("ui.export.history.cancelled") : "")
                    .append(UiText.text("ui.export.history.time")).append(formatDuration(e.durationMs())).append('\n')
                    .append("  ").append(e.destination()).append("\n\n");
        }
        Alert alert = new Alert(Alert.AlertType.INFORMATION);
        DialogLocalization.prepare(alert);
        alert.setTitle(UiText.text("ui.java.export_controller.f9f7911d44")); alert.setHeaderText(UiText.text("ui.java.export_controller.2b0164f7a9") + history.size() + UiText.text("ui.java.export_controller.2f1865f728"));
        if (stage != null) alert.initOwner(stage);
        TextArea area = new TextArea(text.toString()); area.setEditable(false); area.setWrapText(false); area.setPrefSize(760, 440);
        alert.getDialogPane().setContent(area); alert.showAndWait();
    }

    private ExportProfile snapshotProfile(String id, String name) {
        DeviceTargetProfile device = deviceProfileComboBox.getValue();
        return new ExportProfile(id, name, formatComboBox.getValue(), text(destinationField.getText()),
                collisionPolicyComboBox.getValue(), extractOnlyCheckBox.isSelected(),
                text(fileNameTemplateField.getText()), text(subfolderTemplateField.getText()), selectedPostActionId(),
                device == null ? DeviceProfileService.GENERIC_FOLDER_ID : device.id());
    }

    private void loadProfiles(String selectId) {
        List<ExportProfile> profiles = exportProfileService.loadProfiles();
        String requested = text(selectId);
        if (requested.isBlank()) requested = exportLastStateService.load().profileId();
        String requestedId = requested;
        applyingProfile = true;
        try {
            profileComboBox.getItems().setAll(profiles);
            ExportProfile selected = profiles.stream().filter(p -> p.id().equals(requestedId)).findFirst()
                    .orElse(profiles.isEmpty() ? null : profiles.getFirst());
            profileComboBox.setValue(selected);
            if (selected != null) {
                exportLastStateService.rememberProfile(selected.id());
                applyProfile(selected);
            } else {
                exportLastStateService.rememberProfile("");
            }
        } finally { applyingProfile = false; }
        updateProfileButtons();
    }

    private void restoreLastExportState(ExportLastStateService.State saved) {
        if (saved == null) return;
        if (saved.format() != null && formatComboBox.getItems().contains(saved.format())) {
            formatComboBox.setValue(saved.format());
        }
        if (saved.hasDestination()) destinationField.setText(saved.destination());
        if (saved.hasFilenameTemplate()) fileNameTemplateField.setText(saved.filenameTemplate());
        if (saved.hasSubfolderTemplate()) subfolderTemplateField.setText(saved.subfolderTemplate());
        if (saved.collisionPolicy() != null) collisionPolicyComboBox.setValue(saved.collisionPolicy());
        if (saved.extractOnly() != null) extractOnlyCheckBox.setSelected(saved.extractOnly());
        if (saved.hasPostAction()) selectPostAction(saved.postActionId());
    }

    private void persistLastExportState(ExportRequest request) {
        exportLastStateService.save(request);
    }

    private void applyProfile(ExportProfile profile) {
        if (profile == null) { updateProfileButtons(); return; }
        applyingProfile = true;
        try {
            selectDeviceProfile(profile.deviceProfileId());
            if (formatComboBox.getItems().contains(profile.format())) formatComboBox.setValue(profile.format());
            else applyDevicePreferredFormat(deviceProfileComboBox.getValue());
            destinationField.setText(profile.destinationFolder());
            collisionPolicyComboBox.setValue(profile.collisionPolicy());
            extractOnlyCheckBox.setSelected(profile.extractOnly());
            fileNameTemplateField.setText(profile.filenameTemplate());
            subfolderTemplateField.setText(profile.subfolderTemplate());
            selectPostAction(profile.postActionProfileId());
        } finally { applyingProfile = false; }
        updateProfileButtons();
    }

    private void selectDeviceProfile(String id) {
        DeviceTargetProfile selected = deviceProfileService.findById(text(id)).orElse(deviceProfileService.genericFolder());
        deviceProfileComboBox.setValue(selected);
    }

    private void applyDevicePreferredFormat(DeviceTargetProfile profile) {
        if (profile == null || formatComboBox.getItems().isEmpty()) return;
        for (ExportRequest.ExportFormat format : profile.preferredFormats()) {
            if (formatComboBox.getItems().contains(format)) {
                formatComboBox.setValue(format);
                return;
            }
        }
    }

    private ExportRequest.ExportFormat firstSupportedDeviceFormat(DeviceTargetProfile profile,
                                                                   Set<ExportRequest.ExportFormat> supported) {
        if (profile == null || supported == null) return null;
        for (ExportRequest.ExportFormat format : profile.preferredFormats()) {
            if (supported.contains(format)) return format;
        }
        return null;
    }

    private void loadPostActions(String selectId) {
        List<ActionChoice> choices = new java.util.ArrayList<>();
        choices.add(new ActionChoice("", UiText.text("ui.java.export_controller.5a0f8f9876")));
        for (BookActionProfile p : bookActionProfileService.loadProfiles()) {
            if (p.enabled() && !p.commands().isEmpty()) choices.add(new ActionChoice(p.id(), p.name()));
        }
        postActionComboBox.getItems().setAll(choices);
        selectPostAction(selectId);
    }

    private void selectPostAction(String id) {
        String target = text(id);
        ActionChoice choice = postActionComboBox.getItems().stream().filter(c -> c.id().equals(target)).findFirst()
                .orElse(postActionComboBox.getItems().isEmpty() ? null : postActionComboBox.getItems().getFirst());
        postActionComboBox.setValue(choice);
    }

    private String selectedPostActionId() {
        ActionChoice value = postActionComboBox.getValue();
        return value == null ? "" : value.id();
    }

    private void updateProfileButtons() {
        boolean none = profileComboBox == null || profileComboBox.getValue() == null || exportRunning;
        if (updateProfileButton != null) updateProfileButton.setDisable(none);
        if (deleteProfileButton != null) deleteProfileButton.setDisable(none);
    }

    // ==================== FXML ACTIONS ====================

    @FXML private void onChooseDestination() {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(UiText.text("ui.java.export_controller.2b65ac60f3"));
        File dir = chooser.showDialog(stage);
        if (dir != null) {
            destinationField.setText(dir.getAbsolutePath());
            DeviceTargetProfile current = deviceProfileComboBox.getValue();
            DeviceTargetProfile detected = deviceProfileService.detectOrGeneric(dir.toPath());
            if (current == null || DeviceProfileService.GENERIC_FOLDER_ID.equals(current.id())) {
                deviceProfileComboBox.setValue(detected);
            }
        }
    }

    @FXML private void onExport() {
        if (selectedBookIds == null || selectedBookIds.isEmpty()) {
            dialogService.showWarning(UiText.text("ui.java.export_controller.63589c6c88"), UiText.text("ui.java.export_controller.bd09eb5f8f")); return;
        }
        String destPath = text(destinationField.getText());
        if (destPath.isBlank()) { dialogService.showWarning(UiText.text("ui.java.export_controller.177f54f007"), UiText.text("ui.java.export_controller.7fc8d302af")); return; }
        if (formatComboBox.getValue() == null) {
            dialogService.showWarning(UiText.text("ui.java.export_controller.cffa2d08e9"), UiText.text("ui.java.export_controller.f91522aa19")); return;
        }
        Path mountRoot;
        try { mountRoot = Path.of(destPath).toAbsolutePath().normalize(); }
        catch (RuntimeException e) { dialogService.showWarning(UiText.text("ui.java.export_controller.3bfa1270a0"), UiExceptionSupport.message(e)); return; }
        if (!mountRoot.toFile().exists()
                && !dialogService.showConfirmation(UiText.text("ui.java.export_controller.104530734e"), UiText.text("ui.java.export_controller.7fa7530f99"), UiText.text("ui.java.export_controller.855fd3a591") + destPath + "\"?")) return;

        DeviceTargetProfile device = deviceProfileComboBox.getValue();
        if (device == null) device = deviceProfileService.genericFolder();
        Path destination;
        try { destination = device.resolveDestination(mountRoot); }
        catch (RuntimeException e) { dialogService.showWarning(UiText.text("ui.java.export_controller.5b73f19382"), UiExceptionSupport.message(e)); return; }

        ExportProfile selectedProfile = profileComboBox.getValue();
        ExportRequest request = ExportRequest.builder()
                .collectionId(appState.getCurrentLibraryCollectionId())
                .bookIds(selectedBookIds).destinationFolder(destination).format(formatComboBox.getValue())
                .preferredFormats(deviceProfileService.orderedFormats(device, formatComboBox.getValue()))
                .collisionPolicy(collisionPolicyComboBox.getValue())
                .overwriteExisting(collisionPolicyComboBox.getValue() == ExportRequest.CollisionPolicy.OVERWRITE)
                .extractOnly(extractOnlyCheckBox.isSelected())
                .customFileNameTemplate(text(fileNameTemplateField.getText()))
                .subfolderTemplate(text(subfolderTemplateField.getText()))
                .profileId(selectedProfile == null ? "" : selectedProfile.id())
                .profileName(selectedProfile == null ? "" : selectedProfile.name())
                .postActionProfileId(selectedPostActionId())
                .completionPolicy(ExportRequest.CompletionPolicy.EJECT_SAFE)
                .build();

        persistLastExportState(request);

        // Remote books are downloaded automatically before export. The user should not
        // have to run a separate download command or see a "not local" error first.
        setExportRunning(true);
        exportButton.setText(UiText.text("ui.java.export_controller.f28ce2cee1"));
        bookDownloadCoordinator.prepareForExport(selectedBookIds, stage).whenComplete((downloadResult, downloadError) ->
                Platform.runLater(() -> {
                    if (downloadError != null) {
                        setExportRunning(false);
                        dialogService.showError(UiText.text("ui.fxml.integrity_check.export"), UiText.text("ui.java.export_controller.8d664ad092") + UiExceptionSupport.message(downloadError));
                        return;
                    }
                    if (downloadResult != null && downloadResult.failed() > 0) {
                        setExportRunning(false);
                        dialogService.showWarning(UiText.text("ui.fxml.integrity_check.export"),
                                UiText.text("ui.java.export_controller.22f7a7c8cf") + downloadResult.downloaded()
                                        + UiText.text("ui.java.export_controller.47a7bc80cd") + downloadResult.alreadyLocal()
                                        + UiText.text("ui.java.export_controller.bd8a471be4") + downloadResult.failed() + ".");
                        return;
                    }
                    startExportWorker(request);
                }));
    }

    private void startExportWorker(ExportRequest request) {
        exportCancelFlag.set(false);
        appState.getStatusBar().setProgressVisible(true);
        appState.getStatusBar().setProgress(0);
        appState.getStatusBar().setStatusText(UiText.text("ui.java.export_controller.6b383c5932") + selectedBookIds.size());

        executor.submitCollectionScoped(LibraryOperationType.EXPORT, () -> exportToDeviceUseCase.execute(request, exportCancelFlag, progress ->
                        Platform.runLater(() -> {
                            double value = progress.total() <= 0 ? 0 : (double) progress.processed() / progress.total();
                            selectedBooksLabel.setText(String.format(UiText.text("ui.java.export_controller.6463ca5281"), progress.processed(), progress.total(), progress.title()));
                            exportButton.setText(String.format(UiText.text("ui.java.export_controller.44bb0d11ff"), progress.processed(), progress.total()));
                            appState.getStatusBar().setProgress(value);
                            appState.getStatusBar().setStatusText(String.format(UiText.text("ui.java.export_controller.6463ca5281"), progress.processed(), progress.total(), progress.title()));
                        }), this::resolveCollision))
                .whenComplete((result, error) -> Platform.runLater(() -> {
                    if (error != null) {
                        log.error("Помилка експорту", error);
                        setExportRunning(false);
                        appState.getStatusBar().setProgressVisible(false);
                        appState.getStatusBar().setStatusText(UiText.text("ui.java.export_controller.68c7ce9fff"));
                        Throwable cause = com.myhomelibcorp.ui.util.UiExceptionSupport.unwrapAsync(error);
                        dialogService.showError(UiText.text("common.error"), UiText.text("ui.java.export_controller.8e358ef3af")
                                + UiExceptionSupport.message(cause));
                        return;
                    }
                    finishExport(result, request);
                }));
    }

    private ExportCollisionDecision resolveCollision(ExportCollisionContext context) {
        if (Platform.isFxApplicationThread()) return showCollisionDialog(context);
        CompletableFuture<ExportCollisionDecision> answer = new CompletableFuture<>();
        Platform.runLater(() -> {
            try { answer.complete(showCollisionDialog(context)); }
            catch (RuntimeException e) { answer.complete(ExportCollisionDecision.SKIP); }
        });
        try { return answer.get(); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); return ExportCollisionDecision.CANCEL; }
        catch (Exception e) { return ExportCollisionDecision.SKIP; }
    }

    private ExportCollisionDecision showCollisionDialog(ExportCollisionContext context) {
        Alert alert = new Alert(Alert.AlertType.CONFIRMATION);
        DialogLocalization.prepare(alert);
        alert.setTitle(UiText.text("ui.java.export_controller.b905ec44f2"));
        alert.setHeaderText(context.title());
        alert.setContentText(context.existingFile() + UiText.text("ui.java.export_controller.dde3c2d474"));

        ButtonType rename = new ButtonType(UiText.text("ui.java.export_controller.7f902971a1"));
        ButtonType overwrite = new ButtonType(UiText.text("ui.java.export_controller.6a722c8d68"));
        ButtonType skip = new ButtonType(UiText.text("ui.java.export_controller.eaa2b8afb9"));
        ButtonType cancel = new ButtonType(UiText.text("ui.java.export_controller.50488da6fc"), ButtonBar.ButtonData.CANCEL_CLOSE);

        alert.getButtonTypes().setAll(rename, overwrite, skip, cancel);

        ButtonType result = alert.showAndWait().orElse(cancel);
        if (result == rename) return ExportCollisionDecision.RENAME;
        if (result == overwrite) return ExportCollisionDecision.OVERWRITE;
        if (result == skip) return ExportCollisionDecision.SKIP;
        return ExportCollisionDecision.CANCEL;
    }

    private void finishExport(ExportToDeviceUseCase.ExportResult result, ExportRequest request) {
        setExportRunning(false);
        appState.getStatusBar().setProgressVisible(false);
        selectedBooksLabel.setText(selectedBookIds.size() + UiText.text("ui.java.export_controller.968c44e2b7"));
        String summary = String.format(UiText.text("ui.java.export_controller.a4455feed8"),
                result.exported(), result.skipped(), result.failed(), formatDuration(result.durationMs()));
        appState.getStatusBar().setStatusText(result.cancelled() ? UiText.text("ui.java.export_controller.19f9f2c733") + summary : UiText.text("ui.java.export_controller.03fd922ff2") + summary);

        if (result.cancelled()) {
            dialogService.showInfo(UiText.text("ui.java.export_controller.cef3fcb32e"), summary);
        } else if (result.failed() == 0 && result.issues().isEmpty()) {
            // A completed batch is a UI transaction: exported checkboxes are consumed and the
            // active workspace must re-read physical-local state (remote books may have been
            // downloaded as part of export preparation). Failed/cancelled exports deliberately
            // keep the selection so the user can retry.
            bookSelectionService.setSelectedIds(List.copyOf(selectedBookIds), false);
            try {
                successfulExportCallback.run();
            } catch (RuntimeException refreshFailure) {
                log.warn("Експорт завершено, але не вдалося оновити workspace", refreshFailure);
            }
            if (request != null && request.effectiveCompletionPolicy() == ExportRequest.CompletionPolicy.EJECT_SAFE) {
                summary += UiText.text("ui.java.export_controller.a7c94edeb8");
                appState.getStatusBar().setStatusText(summary);
            }
            selectedBooksLabel.setText(summary);
            PauseTransition closeDelay = new PauseTransition(Duration.seconds(1.0));
            closeDelay.setOnFinished(event -> closeDialog());
            closeDelay.play();
        } else if (result.failed() == 0) {
            dialogService.showWarning(UiText.text("ui.java.export_controller.da5e8bba3e"), summary + "\n\n" + renderExportIssues(result));
        } else {
            dialogService.showError(UiText.text("common.error"), summary + "\n\n" + renderExportIssues(result));
        }
    }

    private String renderExportIssues(ExportToDeviceUseCase.ExportResult result) {
        if (result == null || result.issues().isEmpty()) return "";
        return result.issues().stream()
                .map(issue -> UiText.format(issue.key(), issue.arguments().toArray()))
                .collect(java.util.stream.Collectors.joining("\n"));
    }

    private void setExportRunning(boolean running) {
        exportRunning = running;
        exportButton.setDisable(running || selectedBookIds == null || selectedBookIds.isEmpty() || formatComboBox.getValue() == null);
        formatComboBox.setDisable(running); collisionPolicyComboBox.setDisable(running); profileComboBox.setDisable(running);
        deviceProfileComboBox.setDisable(running);
        destinationField.setDisable(running); fileNameTemplateField.setDisable(running); subfolderTemplateField.setDisable(running);
        postActionComboBox.setDisable(running); extractOnlyCheckBox.setDisable(running);
        cancelButton.setDisable(false);
        cancelButton.setText(running ? UiText.text("ui.java.export_controller.cfb5f1d251") : UiText.text("common.close"));
        exportButton.setText(running ? UiText.text("ui.java.export_controller.039ccdd668") + selectedBookIds.size() + UiText.text("ui.java.export_controller.de7370facb") : UiText.text("ui.annotations.export_action"));
        updateProfileButtons();
    }

    @FXML private void onCancel() {
        if (exportRunning) {
            exportCancelFlag.set(true); cancelButton.setDisable(true); cancelButton.setText(UiText.text("ui.java.export_controller.4f50e46aac")); return;
        }
        closeDialog();
    }

    private void closeDialog() { if (stage != null) stage.close(); }
    private static String text(String value) { return value == null ? "" : value.trim(); }
    private static String formatDuration(long ms) {
        long seconds = Math.max(0, ms) / 1000; return String.format("%02d:%02d:%02d", seconds / 3600, (seconds % 3600) / 60, seconds % 60);
    }
    private record ActionChoice(String id, String name) { @Override public String toString() { return name; } }
}
