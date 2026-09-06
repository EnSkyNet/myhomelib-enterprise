package com.myhomelibcorp.application.usecase.book;

import com.myhomelibcorp.application.action.BookActionCommand;
import com.myhomelibcorp.application.action.BookActionExecutionService;
import com.myhomelibcorp.application.action.BookActionProfile;
import com.myhomelibcorp.application.action.BookActionProfileService;
import com.myhomelibcorp.application.action.BookActionRunResult;
import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.executor.ExecutorPort;
import com.myhomelibcorp.domain.model.valueobject.BookId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RunBookActionMaterializationLifecycleTest {
    @TempDir Path temp;

    @Test
    void detachedActionRetainsArchiveMaterializationUntilObservedProcessExits() throws Exception {
        Path cacheDir = temp.resolve("cache");
        ExternalReaderMaterializationCache cache = new ExternalReaderMaterializationCache(
                cacheDir, 1024, 1024, Duration.ofHours(1), Clock.systemUTC());
        cache.initialize();
        Path sourceTemp = Files.writeString(temp.resolve("resolved.fb2"), "private book");

        BookId bookId = BookId.fromLong(42);
        BookDto book = BookDto.builder().id("42").title("Book").fileName("book.zip").archiveEntry("book.fb2").build();
        BookActionProfile profile = new BookActionProfile("p", "Detached", true,
                List.of(new BookActionCommand("ignored", "", "", false)));
        ControlledProcess process = new ControlledProcess();

        LoadBookByIdUseCase load = mock(LoadBookByIdUseCase.class);
        ResolveBookContentUseCase resolve = mock(ResolveBookContentUseCase.class);
        BookActionProfileService profiles = mock(BookActionProfileService.class);
        BookActionExecutionService execution = mock(BookActionExecutionService.class);
        when(load.execute(bookId)).thenReturn(Optional.of(book));
        when(resolve.execute(eq(book), any())).thenReturn(new ResolvedBookContent(sourceTemp, true));
        when(profiles.findById("p")).thenReturn(Optional.of(profile));
        when(execution.execute(eq(profile), anyMap(), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked") Consumer<Process> observer = invocation.getArgument(2, Consumer.class);
            observer.accept(process);
            return BookActionRunResult.success(1);
        });

        RunBookActionUseCase useCase = new RunBookActionUseCase(
                directExecutor(), load, resolve, profiles, execution, cache);
        BookActionRunResult result = useCase.execute(bookId, "p").join();

        assertThat(result.success()).isTrue();
        assertThat(sourceTemp).doesNotExist();
        Path managed;
        try (var files = Files.list(cacheDir)) {
            managed = files.findFirst().orElseThrow();
        }
        assertThat(managed).exists();

        process.complete(0);
        assertThat(managed).doesNotExist();
    }

    private static ExecutorPort directExecutor() {
        return new ExecutorPort() {
            @Override public <T> CompletableFuture<T> submit(Callable<T> task) {
                try { return CompletableFuture.completedFuture(task.call()); }
                catch (Exception e) { return CompletableFuture.failedFuture(e); }
            }
            @Override public void execute(Runnable task) { task.run(); }
        };
    }

    private static final class ControlledProcess extends Process {
        private final CompletableFuture<Process> exit = new CompletableFuture<>();
        private volatile boolean alive = true;
        private volatile int code;
        void complete(int code) { this.code = code; alive = false; exit.complete(this); }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { if (alive) exit.join(); return code; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { try { exit.get(timeout, unit); return true; } catch (Exception e) { return false; } }
        @Override public int exitValue() { if (alive) throw new IllegalThreadStateException(); return code; }
        @Override public void destroy() { complete(0); }
        @Override public Process destroyForcibly() { complete(0); return this; }
        @Override public boolean isAlive() { return alive; }
        @Override public CompletableFuture<Process> onExit() { return exit; }
    }
}
