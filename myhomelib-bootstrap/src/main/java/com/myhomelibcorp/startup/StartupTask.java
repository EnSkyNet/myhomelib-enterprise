package com.myhomelibcorp.startup;

public interface StartupTask {
    String id();
    StartupFailurePolicy failurePolicy();
    StartupTaskResult execute(StartupContext context) throws Exception;
}
