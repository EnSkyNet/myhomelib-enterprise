package com.myhomelibcorp.ui.controller;

import com.myhomelibcorp.application.dto.ExportRequest;
import com.myhomelibcorp.application.export.DeviceProfileService;
import com.myhomelibcorp.application.export.DeviceTargetProfile;
import com.myhomelibcorp.application.export.ExportLastStateService;
import com.myhomelibcorp.application.export.ExportProfile;
import com.myhomelibcorp.application.export.ExportProfileService;
import com.myhomelibcorp.ui.service.DialogService;
import com.myhomelibcorp.ui.service.FileChooserService;
import com.myhomelibcorp.ui.support.UiActionTestSupport;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.TextField;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class ExportActionBehaviorContractTest {
    @BeforeAll static void startFx() throws Exception { UiActionTestSupport.startFx(); }

    @Test
    void onSaveProfileAsRejectsMissingFormatWithVisibleWarning() throws Exception {
        var f = fixture();
        UiActionTestSupport.invoke(f.instance(), "onSaveProfileAs");
        verify(f.dependency(DialogService.class)).showWarning(anyString(), anyString());
        verifyNoInteractions(f.dependency(ExportProfileService.class));
    }

    @Test
    void onUpdateProfilePersistsCurrentVisibleState() throws Exception {
        var f = fixture();
        ExportProfile current = profile("profile-1", "Profile One");
        DeviceTargetProfile generic = generic();
        ExportProfileService profiles = f.dependency(ExportProfileService.class);
        DeviceProfileService devices = f.dependency(DeviceProfileService.class);
        when(profiles.loadProfiles()).thenReturn(List.of(current));
        when(devices.findById(anyString())).thenReturn(Optional.of(generic));
        when(devices.genericFolder()).thenReturn(generic);

        UiActionTestSupport.fx(() -> {
            combo(f.instance(), "profileComboBox").setValue(current);
            combo(f.instance(), "deviceProfileComboBox").setValue(generic);
            combo(f.instance(), "formatComboBox").setValue(ExportRequest.ExportFormat.EPUB);
            combo(f.instance(), "collisionPolicyComboBox").setValue(ExportRequest.CollisionPolicy.RENAME);
            text(f.instance(), "destinationField").setText("/tmp/export");
            text(f.instance(), "fileNameTemplateField").setText("{title}");
            text(f.instance(), "subfolderTemplateField").setText("{author}");
            return null;
        });

        UiActionTestSupport.invoke(f.instance(), "onUpdateProfile");
        verify(profiles).save(argThat(saved -> saved.id().equals("profile-1")
                && saved.format() == ExportRequest.ExportFormat.EPUB
                && saved.destinationFolder().equals("/tmp/export")));
    }

    @Test
    void onDeleteProfileDeletesOnlyAfterConfirmation() throws Exception {
        var f = fixture();
        ExportProfile current = profile("profile-2", "Delete Me");
        ExportProfileService profiles = f.dependency(ExportProfileService.class);
        when(f.dependency(DialogService.class).showConfirmation(anyString(), anyString(), anyString())).thenReturn(true);
        when(profiles.loadProfiles()).thenReturn(List.of());
        when(f.dependency(ExportLastStateService.class).load()).thenReturn(emptyState());
        UiActionTestSupport.fx(() -> { combo(f.instance(), "profileComboBox").setValue(current); return null; });

        UiActionTestSupport.invoke(f.instance(), "onDeleteProfile");
        verify(profiles).delete("profile-2");
    }

    @Test
    void onChooseDestinationPublishesSelectedFolderAndDetectedDevice() throws Exception {
        var f = fixture();
        File selected = Files.createTempDirectory("mhl-export-destination-").toFile();
        DeviceTargetProfile generic = generic();
        DeviceTargetProfile detected = new DeviceTargetProfile("device", "Detected", List.of(ExportRequest.ExportFormat.EPUB), "Books", false);
        when(f.dependency(FileChooserService.class).chooseDirectory(any(), anyString())).thenReturn(selected);
        when(f.dependency(DeviceProfileService.class).detectOrGeneric(selected.toPath())).thenReturn(detected);
        UiActionTestSupport.fx(() -> { combo(f.instance(), "deviceProfileComboBox").setValue(generic); return null; });

        UiActionTestSupport.invoke(f.instance(), "onChooseDestination");
        assertThat(UiActionTestSupport.fx(() -> text(f.instance(), "destinationField").getText())).isEqualTo(selected.getAbsolutePath());
        assertThat(UiActionTestSupport.fx(() -> combo(f.instance(), "deviceProfileComboBox").getValue())).isEqualTo(detected);
    }

    private static UiActionTestSupport.Fixture<ExportController> fixture() throws Exception {
        var f = UiActionTestSupport.constructWithMocks(ExportController.class);
        UiActionTestSupport.fx(() -> {
            UiActionTestSupport.setField(f.instance(), "profileComboBox", new ComboBox<ExportProfile>());
            UiActionTestSupport.setField(f.instance(), "deviceProfileComboBox", new ComboBox<DeviceTargetProfile>());
            UiActionTestSupport.setField(f.instance(), "formatComboBox", new ComboBox<ExportRequest.ExportFormat>());
            UiActionTestSupport.setField(f.instance(), "destinationField", new TextField());
            UiActionTestSupport.setField(f.instance(), "fileNameTemplateField", new TextField());
            UiActionTestSupport.setField(f.instance(), "subfolderTemplateField", new TextField());
            UiActionTestSupport.setField(f.instance(), "collisionPolicyComboBox", new ComboBox<ExportRequest.CollisionPolicy>());
            UiActionTestSupport.setField(f.instance(), "postActionComboBox", new ComboBox<>());
            UiActionTestSupport.setField(f.instance(), "extractOnlyCheckBox", new CheckBox());
            UiActionTestSupport.setField(f.instance(), "updateProfileButton", new Button());
            UiActionTestSupport.setField(f.instance(), "deleteProfileButton", new Button());
            return null;
        });
        return f;
    }

    @SuppressWarnings("unchecked")
    private static <T> ComboBox<T> combo(Object target, String field) throws Exception {
        return (ComboBox<T>) UiActionTestSupport.getField(target, field);
    }
    private static TextField text(Object target, String field) throws Exception {
        return (TextField) UiActionTestSupport.getField(target, field);
    }
    private static DeviceTargetProfile generic() {
        return new DeviceTargetProfile(DeviceProfileService.GENERIC_FOLDER_ID, "Folder", List.of(ExportRequest.ExportFormat.EPUB), "", true);
    }
    private static ExportProfile profile(String id, String name) {
        return new ExportProfile(id, name, ExportRequest.ExportFormat.EPUB, "/tmp", ExportRequest.CollisionPolicy.RENAME,
                false, "{title}", "", "", DeviceProfileService.GENERIC_FOLDER_ID);
    }
    private static ExportLastStateService.State emptyState() {
        return new ExportLastStateService.State("", null, null, false, "", false, "", false, "", false, "", null);
    }
}
