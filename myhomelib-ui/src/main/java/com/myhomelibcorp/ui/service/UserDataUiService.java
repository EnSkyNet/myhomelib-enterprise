package com.myhomelibcorp.ui.service;

import com.myhomelibcorp.application.port.out.exchange.UserDataExchangePort;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.springframework.stereotype.Service;

import java.io.File;

@Service
public class UserDataUiService {
    private final UserDataExchangePort exchange; private final DialogService dialogs;
    public UserDataUiService(UserDataExchangePort exchange, DialogService dialogs){this.exchange=exchange;this.dialogs=dialogs;}
    public void exportData(Window owner){
        FileChooser fc=new FileChooser();fc.setTitle("Експорт користувацьких даних");fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("MyHomeLib user data","*.mhluserdata.json"));fc.setInitialFileName("myhomelib-user-data.mhluserdata.json");
        File f=fc.showSaveDialog(owner);if(f==null)return; try{exchange.exportTo(f.toPath());dialogs.showInfo("Експорт завершено","Користувацькі дані збережено:\n"+f);}catch(Exception e){dialogs.showError("Помилка експорту",e.getMessage());}
    }
    public void importData(Window owner){
        FileChooser fc=new FileChooser();fc.setTitle("Імпорт користувацьких даних");fc.getExtensionFilters().add(new FileChooser.ExtensionFilter("MyHomeLib user data","*.json","*.mhluserdata.json"));
        File f=fc.showOpenDialog(owner);if(f==null)return; try{var r=exchange.importFrom(f.toPath());dialogs.showInfo("Імпорт завершено",String.format("Книги: %d\nГрупи: %d\nЗакладки: %d\nПозиції: %d",r.booksUpdated(),r.groupsUpdated(),r.bookmarksUpdated(),r.progressUpdated()));}catch(Exception e){dialogs.showError("Помилка імпорту",e.getMessage());}
    }
}
