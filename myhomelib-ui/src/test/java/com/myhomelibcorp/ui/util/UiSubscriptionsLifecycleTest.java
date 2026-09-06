package com.myhomelibcorp.ui.util;

import javafx.beans.property.SimpleStringProperty;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class UiSubscriptionsLifecycleTest {

    @Test
    void oneHundredAttachDisposeCyclesDoNotAccumulateCallbacks() {
        SimpleStringProperty longLivedState = new SimpleStringProperty("initial");
        AtomicInteger callbacks = new AtomicInteger();

        for (int i = 0; i < 100; i++) {
            UiSubscriptions subscriptions = new UiSubscriptions();
            subscriptions.listen(longLivedState, (obs, oldValue, newValue) -> callbacks.incrementAndGet());

            longLivedState.set("active-" + i);
            assertEquals(i + 1, callbacks.get(), "exactly the current controller listener must fire");

            subscriptions.close();
            assertTrue(subscriptions.isClosed());

            longLivedState.set("disposed-" + i);
            assertEquals(i + 1, callbacks.get(), "disposed listeners must stay silent");

            // Lifecycle close can be called defensively more than once.
            subscriptions.close();
        }

        assertEquals(100, callbacks.get());
    }

    @Test
    void registrationsAreRejectedAfterDispose() {
        UiSubscriptions subscriptions = new UiSubscriptions();
        subscriptions.close();
        assertThrows(IllegalStateException.class,
                () -> subscriptions.listen(new SimpleStringProperty(), (obs, oldValue, newValue) -> { }));
    }
}
