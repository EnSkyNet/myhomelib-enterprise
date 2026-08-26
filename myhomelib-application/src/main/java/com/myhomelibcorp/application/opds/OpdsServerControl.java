package com.myhomelibcorp.application.opds;

public interface OpdsServerControl {
    OpdsServerStatus start(OpdsServerSettings settings);
    void stop();
    OpdsServerStatus status();
}
