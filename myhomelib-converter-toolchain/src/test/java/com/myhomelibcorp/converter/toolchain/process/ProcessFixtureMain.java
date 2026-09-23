package com.myhomelibcorp.converter.toolchain.process;

public final class ProcessFixtureMain {
    private ProcessFixtureMain() { }

    public static void main(String[] args) throws Exception {
        long millis = args.length == 0 ? 5_000L : Long.parseLong(args[0]);
        Thread.sleep(millis);
    }
}
