package com.myhomelibcorp.converter.toolchain.process;

import com.myhomelibcorp.shared.util.ProcessExecutionSupport;

public final class ProcessTreeTerminator {
    private ProcessTreeTerminator() { }
    public static void terminate(Process process) { ProcessExecutionSupport.terminate(process); }
}
