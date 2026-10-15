package com.myhomelibcorp.ui.presenter;

import com.myhomelibcorp.application.dto.BookDto;
import com.myhomelibcorp.application.port.out.cover.CoverExtractor;
import com.myhomelibcorp.ui.service.UiBackgroundExecutor;
import com.myhomelibcorp.ui.viewmodel.ApplicationState;
import javafx.application.Platform;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CoverPresenterFlickerRegressionTest {
    @BeforeAll
    static void bootFx() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        try { Platform.startup(latch::countDown); }
        catch (IllegalStateException alreadyRunning) { latch.countDown(); }
        assertThat(latch.await(8, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void enrichmentDoesNotBlankOrReplaceIdenticalCoverButRealChangesStillRender() throws Exception {
        Harness h = new Harness();
        byte[] front = png(0xff4281dd);
        byte[] revised = png(0xffdd8142);
        when(h.extractor.extractCover(any())).thenReturn(front, front, revised, front);
        BookDto catalogue = book("book-A", "artifact-1");
        BookDto enriched = book("book-A", "artifact-2");
        BookDto newRepresentation = book("book-A", "artifact-3");
        BookDto another = book("book-B", "artifact-1");
        AtomicReference<Image> first = new AtomicReference<>();
        AtomicReference<Image> updated = new AtomicReference<>();

        fx(() -> h.presenter.showCover(catalogue));
        h.completeNext();
        fx(() -> first.set(h.view.getImage()));
        assertThat(first.get()).isNotNull();

        fx(() -> {
            h.presenter.showCover(enriched);
            assertThat(h.view.getImage()).isSameAs(first.get());
        });
        h.completeNext();
        fx(() -> assertThat(h.view.getImage()).isSameAs(first.get()));

        fx(() -> {
            h.presenter.showCover(newRepresentation);
            assertThat(h.view.getImage()).isSameAs(first.get());
        });
        h.completeNext();
        fx(() -> {
            updated.set(h.view.getImage());
            assertThat(updated.get()).isNotSameAs(first.get());
        });

        fx(() -> {
            h.presenter.showCover(another);
            assertThat(h.view.getImage()).isNull();
        });
        h.completeNext();
        fx(() -> assertThat(h.view.getImage()).isNotNull());
        verify(h.extractor, times(4)).extractCover(any());
    }

    @Test
    void olderCompletionCannotOverwriteNewBookOrClearRequestedReplacement() throws Exception {
        Harness h = new Harness();
        byte[] front = png(0xff2367ab);
        when(h.extractor.extractCover(any())).thenReturn(front);
        fx(() -> h.presenter.showCover(book("A", "original")));
        // The first request is still queued; changing the book must cancel it.
        fx(() -> h.presenter.showCover(book("B", "next")));
        h.completeNext();
        fx(() -> assertThat(h.view.getImage()).isNull());
        h.completeNext();
        fx(() -> assertThat(h.view.getImage()).isNotNull());
    }

    private static BookDto book(String id, String artifact) {
        return BookDto.builder().id(id).title(id).fileName("book.fb2")
                .folder("shelf").archiveEntry("book.fb2").collectionRoot("/library")
                .preferredArtifactId(artifact).build();
    }

    private static byte[] png(int argb) throws Exception {
        BufferedImage image = new BufferedImage(2, 2, BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0, 0, argb);
        image.setRGB(1, 0, argb);
        image.setRGB(0, 1, argb);
        image.setRGB(1, 1, argb);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        if (!ImageIO.write(image, "png", out)) throw new IllegalStateException("PNG encoder unavailable");
        return out.toByteArray();
    }

    private static void fx(Runnable action) throws Exception {
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Platform.runLater(() -> {
            try { action.run(); }
            catch (Throwable ex) { failure.set(ex); }
            finally { done.countDown(); }
        });
        assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();
        if (failure.get() != null) throw new AssertionError(failure.get());
    }

    private static final class Harness {
        final CoverExtractor extractor = mock(CoverExtractor.class);
        final UiBackgroundExecutor background = mock(UiBackgroundExecutor.class);
        final ApplicationState state = mock(ApplicationState.class);
        final CoverPresenter presenter = new CoverPresenter(extractor, background, state);
        final ImageView view = new ImageView();
        final List<Pending> requests = new ArrayList<>();
        int cursor;

        @SuppressWarnings({"unchecked", "rawtypes"})
        Harness() {
            when(state.getCurrentLibraryCollectionId()).thenReturn("collection-1");
            when(background.submitInteractiveCancellable(any())).thenAnswer(invocation -> {
                Callable<?> action = invocation.getArgument(0);
                CompletableFuture<Object> completion = new CompletableFuture<>();
                UiBackgroundExecutor.CancellableSubmission submission = mock(UiBackgroundExecutor.CancellableSubmission.class);
                when(submission.completion()).thenReturn(completion);
                requests.add(new Pending(action, completion));
                return submission;
            });
            presenter.bind(view);
        }

        void completeNext() throws Exception {
            Pending next = requests.get(cursor++);
            Object result = next.action.call();
            next.completion.complete(result);
            // Ensure pending Platform.runLater completion has run.
            fx(() -> {});
        }
    }

    private record Pending(Callable<?> action, CompletableFuture<Object> completion) {}
}
