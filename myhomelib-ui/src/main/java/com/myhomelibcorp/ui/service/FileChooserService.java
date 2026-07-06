package com.myhomelibcorp.ui.service;

import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Stage;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;

@Service
public class FileChooserService {

    public File chooseFile(Stage owner, String title, List<FileChooser.ExtensionFilter> filters) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(title);
        if (filters != null) {
            fileChooser.getExtensionFilters().addAll(filters);
        }
        return fileChooser.showOpenDialog(owner);
    }

    public File chooseDirectory(Stage owner, String title) {
        DirectoryChooser directoryChooser = new DirectoryChooser();
        directoryChooser.setTitle(title);
        return directoryChooser.showDialog(owner);
    }

    public File chooseFileToSave(Stage owner, String title, String initialFileName) {
        FileChooser fileChooser = new FileChooser();
        fileChooser.setTitle(title);
        if (initialFileName != null) {
            fileChooser.setInitialFileName(initialFileName);
        }
        return fileChooser.showSaveDialog(owner);
    }
}