package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.port.out.settings.ApplicationSettingsPort;
import com.myhomelibcorp.application.usecase.collection.UpdateCollectionPropertiesUseCase;
import com.myhomelibcorp.domain.model.collection.Collection;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.geometry.Insets;
import javafx.scene.control.*;
import javafx.scene.layout.GridPane;
import javafx.stage.DirectoryChooser;
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

    public Collection show(Window owner) {
        Collection c = state.getCurrentLibraryCollection();
        if (c == null) { alert(owner, "Колекцію не вибрано"); return null; }

        Dialog<ButtonType> d = new Dialog<>(); d.setTitle("Властивості колекції"); d.initOwner(owner);
        d.getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        TextField name=new TextField(c.getName()); TextField root=new TextField(c.getRootFolder()==null?"":c.getRootFolder().toString());
        TextField user=new TextField(nvl(c.getUser())); PasswordField pass=new PasswordField();
        try { pass.setText(nvl(c.getDecryptedPassword())); } catch(Exception ignored) { }
        TextField baseUrl=new TextField(nvl(c.getUrl())); TextField inpxUrl=new TextField(settings.get("collection."+c.getId()+".inpxUrl",""));
        TextArea notes=new TextArea(nvl(c.getNotes())); notes.setPrefRowCount(3);
        ComboBox<String> type=new ComboBox<>(); type.getItems().addAll("Локальна FB2/змішана", "INPX/архівна", "Віддалена/online"); type.getSelectionModel().select(Math.max(0,Math.min(2,c.getType())));
        Button browse=new Button("Обрати..."); browse.setOnAction(e->{DirectoryChooser dc=new DirectoryChooser();dc.setTitle("Коренева папка колекції");try{Path p=Path.of(root.getText());if(java.nio.file.Files.isDirectory(p))dc.setInitialDirectory(p.toFile());}catch(Exception ignored){}var f=dc.showDialog(owner);if(f!=null)root.setText(f.toPath().toString());});
        GridPane g=new GridPane();g.setHgap(8);g.setVgap(8);g.setPadding(new Insets(12));int r=0;
        g.addRow(r++,new Label("Назва:"),name);g.addRow(r++,new Label("Тип:"),type);g.addRow(r++,new Label("Коренева папка:"),root,browse);
        g.addRow(r++,new Label("Base URL книг/архівів:"),baseUrl);g.addRow(r++,new Label("URL INPX для оновлення:"),inpxUrl);
        g.addRow(r++,new Label("Користувач:"),user);g.addRow(r++,new Label("Пароль:"),pass);g.addRow(r++,new Label("Нотатки:"),notes);
        d.getDialogPane().setContent(g);
        if(d.showAndWait().orElse(ButtonType.CANCEL)!=ButtonType.OK)return null;
        try {
            Path rootPath=root.getText().isBlank()?c.getRootFolder():Path.of(root.getText());
            Collection updated=updateUseCase.execute(c,name.getText(),rootPath,type.getSelectionModel().getSelectedIndex(),user.getText(),pass.getText(),baseUrl.getText(),notes.getText());
            settings.put("collection."+updated.getId()+".inpxUrl",inpxUrl.getText().trim()); state.setCurrentLibraryCollection(updated); return updated;
        } catch(Exception ex) { alert(owner,"Не вдалося зберегти властивості: "+ex.getMessage()); return null; }
    }
    private static String nvl(String s){return s==null?"":s;}
    private static void alert(Window owner,String text){Alert a=new Alert(Alert.AlertType.ERROR,text,ButtonType.OK);a.setTitle("MyHomeLib");if(owner!=null)a.initOwner(owner);a.showAndWait();}
}
