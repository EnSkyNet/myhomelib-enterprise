package com.myhomelibcorp.ui.viewmodel;

import com.myhomelibcorp.domain.model.collection.CollectionType;
import javafx.beans.property.*;

import java.nio.file.Path;

public class CollectionWizardViewModel {

    private final StringProperty name = new SimpleStringProperty();
    private final ObjectProperty<Path> rootFolder = new SimpleObjectProperty<>();
    private final ObjectProperty<Path> dbFile = new SimpleObjectProperty<>();
    private final StringProperty sourcePath = new SimpleStringProperty();
    private final StringProperty url = new SimpleStringProperty();
    private final StringProperty user = new SimpleStringProperty();
    private final StringProperty password = new SimpleStringProperty();
    private final StringProperty connectionScript = new SimpleStringProperty();
    private final ObjectProperty<CollectionType> type = new SimpleObjectProperty<>(CollectionType.FB2_LOCAL);
    private final BooleanProperty importOnCreate = new SimpleBooleanProperty(true);
    private final BooleanProperty createIndex = new SimpleBooleanProperty(true);
    private final IntegerProperty currentStep = new SimpleIntegerProperty(0);
    private final StringProperty errorMessage = new SimpleStringProperty();

    // Геттери властивостей
    public StringProperty nameProperty() { return name; }
    public ObjectProperty<Path> rootFolderProperty() { return rootFolder; }
    public ObjectProperty<Path> dbFileProperty() { return dbFile; }
    public StringProperty sourcePathProperty() { return sourcePath; }
    public StringProperty urlProperty() { return url; }
    public StringProperty userProperty() { return user; }
    public StringProperty passwordProperty() { return password; }
    public StringProperty connectionScriptProperty() { return connectionScript; }
    public ObjectProperty<CollectionType> typeProperty() { return type; }
    public BooleanProperty importOnCreateProperty() { return importOnCreate; }
    public BooleanProperty createIndexProperty() { return createIndex; }
    public IntegerProperty currentStepProperty() { return currentStep; }
    public StringProperty errorMessageProperty() { return errorMessage; }

    // Геттери та сеттери
    public String getName() { return name.get(); }
    public void setName(String name) { this.name.set(name); }

    public Path getRootFolder() { return rootFolder.get(); }
    public void setRootFolder(Path rootFolder) { this.rootFolder.set(rootFolder); }

    public Path getDbFile() { return dbFile.get(); }
    public void setDbFile(Path dbFile) { this.dbFile.set(dbFile); }

    public String getSourcePath() { return sourcePath.get(); }
    public void setSourcePath(String sourcePath) { this.sourcePath.set(sourcePath); }
    public String getUrl() { return url.get(); }
    public void setUrl(String value) { url.set(value); }
    public String getUser() { return user.get(); }
    public void setUser(String value) { user.set(value); }
    public String getPassword() { return password.get(); }
    public void setPassword(String value) { password.set(value); }
    public String getConnectionScript() { return connectionScript.get(); }
    public void setConnectionScript(String value) { connectionScript.set(value); }

    public CollectionType getType() { return type.get(); }
    public void setType(CollectionType type) { this.type.set(type); }

    public boolean isImportOnCreate() { return importOnCreate.get(); }
    public void setImportOnCreate(boolean importOnCreate) { this.importOnCreate.set(importOnCreate); }

    public boolean isCreateIndex() { return createIndex.get(); }
    public void setCreateIndex(boolean createIndex) { this.createIndex.set(createIndex); }

    public int getCurrentStep() { return currentStep.get(); }
    public void setCurrentStep(int currentStep) { this.currentStep.set(currentStep); }

    public String getErrorMessage() { return errorMessage.get(); }
    public void setErrorMessage(String errorMessage) { this.errorMessage.set(errorMessage); }

    public void clear() {
        name.set("");
        rootFolder.set(null);
        dbFile.set(null);
        sourcePath.set("");
        url.set("");
        user.set("");
        password.set("");
        connectionScript.set("");
        type.set(CollectionType.FB2_LOCAL);
        importOnCreate.set(true);
        createIndex.set(true);
        currentStep.set(0);
        errorMessage.set("");
    }

    public boolean isStepValid(int step) {
        return switch (step) {
            case 0 -> isStep1Valid();
            case 1 -> isStep2Valid();
            case 2 -> true;
            default -> false;
        };
    }

    private boolean isStep1Valid() {
        return getName() != null && !getName().isBlank();
    }

    private boolean isStep2Valid() {
        CollectionType type = getType() == null ? CollectionType.FB2_LOCAL : getType();
        boolean sourceOk = getSourcePath() != null && !getSourcePath().isBlank();
        if (type.requiresUrl() && (getUrl() == null || getUrl().isBlank())) return false;
        return !type.requiresSource() || sourceOk;
    }

    public boolean isValid() {
        return isStep1Valid() && isStep2Valid();
    }
}