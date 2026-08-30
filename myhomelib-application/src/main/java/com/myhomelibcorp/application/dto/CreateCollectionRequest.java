package com.myhomelibcorp.application.dto;

import lombok.Builder;
import lombok.Value;

import java.nio.file.Path;

@Value
@Builder
public class CreateCollectionRequest {
    String name;
    Path rootFolder;
    Path dbFile;
    String sourcePath;
    int typeCode;
    boolean importOnCreate;
    boolean createIndex;
    String user;
    String password;
    String url;
    String notes;
    String connectionScript;
}