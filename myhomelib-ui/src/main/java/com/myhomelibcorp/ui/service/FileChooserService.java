package com.myhomelibcorp.ui.service;

import javafx.stage.DirectoryChooser;
import javafx.stage.FileChooser;
import javafx.stage.Window;
import org.springframework.stereotype.Service;

import java.io.File;
import java.util.List;

/** Single testable boundary that owns native JavaFX file/directory chooser construction. */
@Service
public class FileChooserService {

    public File chooseFile(Window owner, String title, List<FileChooser.ExtensionFilter> filters) {
        return chooseFile(owner, title, filters, null);
    }

    public File chooseFile(Window owner, String title, List<FileChooser.ExtensionFilter> filters, File initialDirectory) {
        FileChooser chooser = fileChooser(title, null, filters, initialDirectory);
        try {
            return chooser.showOpenDialog(owner);
        } catch (IllegalArgumentException invalidInitialDirectory) {
            return fileChooser(title, null, filters, null).showOpenDialog(owner);
        }
    }

    public File chooseDirectory(Window owner, String title) {
        return chooseDirectory(owner, title, null);
    }

    public File chooseDirectory(Window owner, String title, File initialDirectory) {
        DirectoryChooser chooser = directoryChooser(title, initialDirectory);
        try {
            return chooser.showDialog(owner);
        } catch (IllegalArgumentException invalidInitialDirectory) {
            return directoryChooser(title, null).showDialog(owner);
        }
    }

    public File chooseFileToSave(Window owner, String title, String initialFileName) {
        return chooseFileToSave(owner, title, initialFileName, List.of());
    }

    public File chooseFileToSave(Window owner, String title, String initialFileName,
                                 List<FileChooser.ExtensionFilter> filters) {
        return chooseFileToSave(owner, title, initialFileName, filters, null);
    }

    public File chooseFileToSave(Window owner, String title, String initialFileName,
                                 List<FileChooser.ExtensionFilter> filters, File initialDirectory) {
        FileChooser chooser = fileChooser(title, initialFileName, filters, initialDirectory);
        try {
            return chooser.showSaveDialog(owner);
        } catch (IllegalArgumentException invalidInitialDirectory) {
            return fileChooser(title, initialFileName, filters, null).showSaveDialog(owner);
        }
    }

    private static FileChooser fileChooser(String title, String initialFileName,
                                           List<FileChooser.ExtensionFilter> filters, File initialDirectory) {
        FileChooser chooser = new FileChooser();
        chooser.setTitle(title);
        if (initialFileName != null) chooser.setInitialFileName(initialFileName);
        if (filters != null) chooser.getExtensionFilters().addAll(filters);
        if (initialDirectory != null && initialDirectory.isDirectory()) chooser.setInitialDirectory(initialDirectory);
        return chooser;
    }

    private static DirectoryChooser directoryChooser(String title, File initialDirectory) {
        DirectoryChooser chooser = new DirectoryChooser();
        chooser.setTitle(title);
        if (initialDirectory != null && initialDirectory.isDirectory()) chooser.setInitialDirectory(initialDirectory);
        return chooser;
    }
}
