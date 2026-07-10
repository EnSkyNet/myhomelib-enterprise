package com.myhomelibcorp.application.port.out.executor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Callable;

public interface ExecutorPort {
    <T> CompletableFuture<T> submit(Callable<T> task);
    void execute(Runnable task);
}