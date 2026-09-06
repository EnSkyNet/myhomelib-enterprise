package com.myhomelibcorp.ui.util;

import javafx.beans.Observable;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.collections.ListChangeListener;
import javafx.collections.ObservableList;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/**
 * Owns listener registrations made by a transient JavaFX controller.
 *
 * <p>FXML controllers that subscribe to long-lived application/view-model state must close this
 * registry from their workspace/dialog lifecycle. Registrations are removed in reverse order and
 * close is idempotent, so repeated dispose calls are safe.</p>
 */
public final class UiSubscriptions implements AutoCloseable {

    private final Deque<Runnable> removers = new ArrayDeque<>();
    private boolean closed;

    public <T> void listen(ObservableValue<T> observable, ChangeListener<? super T> listener) {
        Objects.requireNonNull(observable, "observable");
        Objects.requireNonNull(listener, "listener");
        ensureOpen();
        observable.addListener(listener);
        removers.push(() -> observable.removeListener(listener));
    }

    public <E> void listen(ObservableList<E> observable, ListChangeListener<? super E> listener) {
        Objects.requireNonNull(observable, "observable");
        Objects.requireNonNull(listener, "listener");
        ensureOpen();
        observable.addListener(listener);
        removers.push(() -> observable.removeListener(listener));
    }

    public void invalidation(Observable observable, javafx.beans.InvalidationListener listener) {
        Objects.requireNonNull(observable, "observable");
        Objects.requireNonNull(listener, "listener");
        ensureOpen();
        observable.addListener(listener);
        removers.push(() -> observable.removeListener(listener));
    }

    public boolean isClosed() {
        return closed;
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        RuntimeException first = null;
        while (!removers.isEmpty()) {
            try {
                removers.pop().run();
            } catch (RuntimeException ex) {
                if (first == null) first = ex;
                else first.addSuppressed(ex);
            }
        }
        if (first != null) throw first;
    }

    private void ensureOpen() {
        if (closed) throw new IllegalStateException("UI subscriptions already disposed");
    }
}
