package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.ui.util.UiExceptionSupport;
import com.myhomelibcorp.ui.collection.CollectionUiCommitGuard;
import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.usecase.collection.UpdateCollectionPropertiesUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.domain.model.collection.CollectionType;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.file.Path;

@Component
@RequiredArgsConstructor
public class CollectionPropertiesUiService {
    private final ApplicationState state;
    private final UpdateCollectionPropertiesUseCase updateUseCase;
    private final ApplicationSettingsPort settings;
    private final CollectionUiCommitGuard collectionUiCommitGuard;
    private final FileChooserService fileChooserService;

    public Collection show(Window owner) {
        Collection c = state.getCurrentLibraryCollection();
        if (c == null) { alert(owner, UiText.text("ui.main.scope.none")); return null; }

        Dialog<ButtonType> d = new Dialog<>();
        DialogLocalization.prepare(d); d.setTitle(UiText.text("ui.java.collection_properties_ui_service.beb9fbc14d")); d.initOwner(owner);
        ButtonType saveButton = new ButtonType(UiText.text("common.save"), ButtonBar.ButtonData.OK_DONE);
        d.getDialogPane().getButtonTypes().addAll(saveButton, ButtonType.CANCEL);
        TextField name=new TextField(c.getName()); TextField root=new TextField(c.getRootFolder()==null?"":c.getRootFolder().toString());
        TextField user=new TextField(nvl(c.getUser())); PasswordField pass=new PasswordField();
        final boolean[] passwordReadable = {true};
        try { pass.setText(nvl(c.getDecryptedPassword())); }
        catch(Exception decryptError) {
            passwordReadable[0] = false;
            pass.setDisable(true);
            pass.setPromptText(UiText.text("ui.java.collection_properties_ui_service.b27ca134f6"));
        }
        TextField baseUrl=new TextField(nvl(c.getUrl())); TextField inpxUrl=new TextField(settings.get("collection."+c.getId()+".inpxUrl",""));
        TextArea notes=new TextArea(nvl(c.getNotes())); notes.setPrefRowCount(3);
        TextArea connectionScript=new TextArea(nvl(c.getConnectionScript())); connectionScript.setPrefRowCount(8);
        connectionScript.setPromptText(UiText.text("ui.collection.connection_script_hint"));
        ComboBox<CollectionType> type=new ComboBox<>();
        type.getItems().setAll(CollectionType.values());
        type.setConverter(new javafx.util.StringConverter<>() {
            @Override public String toString(CollectionType value) {
                return value == null ? "" : UiText.text(value.getLocalizationKey());
            }
            @Override public CollectionType fromString(String value) { return type.getValue(); }
        });
        type.setValue(CollectionType.fromCode(c.getType()));
        Button browse=new Button(UiText.text("ui.export.destination.choose")); browse.setOnAction(e->{
            java.io.File initial = null;
            try {
                if (root.getText()!=null && !root.getText().isBlank()) {
                    Path path=Path.of(root.getText()).toAbsolutePath().normalize();
                    if (java.nio.file.Files.isDirectory(path) && java.nio.file.Files.isReadable(path)) initial=path.toFile();
                }
            } catch (Exception ignored) { }
            java.io.File f=fileChooserService.chooseDirectory(owner,
                    UiText.text("ui.java.collection_properties_ui_service.00ad58e3df"), initial);
            if(f!=null)root.setText(f.toPath().toString());
        });
        GridPane g=new GridPane();g.setHgap(8);g.setVgap(8);g.setPadding(new Insets(12));int r=0;
        g.addRow(r++,new Label(UiText.text("common.name.label")),name);g.addRow(r++,new Label(UiText.text("ui.fxml.collection_wizard.type")),type);g.addRow(r++,new Label(UiText.text("ui.fxml.collection_wizard.root_folder")),root,browse);
        g.addRow(r++,new Label(UiText.text("ui.java.collection_properties_ui_service.0a8f30df84")),baseUrl);g.addRow(r++,new Label(UiText.text("ui.fxml.collection_wizard.inpx_update_url")),inpxUrl);
        g.addRow(r++,new Label(UiText.text("ui.fxml.collection_wizard.user")),user);g.addRow(r++,new Label(UiText.text("ui.fxml.collection_wizard.password")),pass);g.addRow(r++,new Label(UiText.text("ui.java.collection_properties_ui_service.76512279b2")),notes);
        g.addRow(r++,new Label(UiText.text("ui.java.collection_properties_ui_service.683f2d1289")),connectionScript);
        d.getDialogPane().setContent(g);
        if(d.showAndWait().orElse(ButtonType.CANCEL)!=saveButton)return null;
        try {
            Path rootPath=root.getText().isBlank()?c.getRootFolder():Path.of(root.getText());
            CollectionType selectedType = type.getValue() == null ? CollectionType.fromCode(c.getType()) : type.getValue();
            String passwordValue = passwordReadable[0] ? pass.getText() : c.getPassword();
            Collection updated=updateUseCase.execute(c,name.getText(),rootPath,selectedType.getCode(),user.getText(),passwordValue,baseUrl.getText(),notes.getText(),connectionScript.getText());
            settings.put("collection."+updated.getId()+".inpxUrl",inpxUrl.getText().trim());
            collectionUiCommitGuard.runIfStillActive(updated.getId(), () -> state.setCurrentLibraryCollection(updated));
            return updated;
        } catch(Exception ex) { alert(owner,UiText.text("ui.java.collection_properties_ui_service.b7bbec51f0")+UiExceptionSupport.message(ex)); return null; }
    }
    private static String nvl(String s){return s==null?"":s;}
    private static void alert(Window owner,String text){
        Alert a=DialogLocalization.prepare(new Alert(Alert.AlertType.ERROR,text,ButtonType.OK));
        a.setTitle(UiText.text("ui.brand.name"));
        if(owner!=null)a.initOwner(owner);
        a.showAndWait();
    }
}
