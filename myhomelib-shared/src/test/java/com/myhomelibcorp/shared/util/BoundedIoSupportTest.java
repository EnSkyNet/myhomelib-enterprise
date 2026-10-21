package com.myhomelibcorp.shared.util;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

class BoundedIoSupportTest {
    @Test
    void fullReadBelowLimit() throws Exception {
        assertArrayEquals(new byte[] {1, 2}, BoundedIoSupport.readFully(new ByteArrayInputStream(new byte[] {1, 2}), 3));
    }

    @Test
    void fullReadAtExactLimit() throws Exception {
        assertArrayEquals(new byte[] {1, 2}, BoundedIoSupport.readFully(new ByteArrayInputStream(new byte[] {1, 2}), 2));
    }

    @Test
    void oneByteBeyondLimitIsRejected() {
        IOException error = assertThrows(IOException.class,
                () -> BoundedIoSupport.readFully(new ByteArrayInputStream(new byte[] {1, 2, 3}), 2));
        assertTrue(error.getMessage().contains("2 bytes"));
    }

    @Test
    void maximumIntegerLimitDoesNotOverflow() throws Exception {
        assertArrayEquals(new byte[] {1, 2},
                BoundedIoSupport.readFully(new ByteArrayInputStream(new byte[] {1, 2}), Integer.MAX_VALUE));
    }

    @Test
    void emptyStreamIsAccepted() throws Exception {
        assertArrayEquals(new byte[0], BoundedIoSupport.readFully(new ByteArrayInputStream(new byte[0]), 1));
    }

    @Test
    void prefixStopsAtLimitWithoutConsumingFollowingBytes() throws Exception {
        ByteArrayInputStream input = new ByteArrayInputStream(new byte[] {1, 2, 3});
        assertArrayEquals(new byte[] {1, 2}, BoundedIoSupport.readPrefix(input, 2));
        assertEquals(3, input.read());
    }

    @Test
    void rejectsNullAndInvalidLimits() {
        assertThrows(IllegalArgumentException.class, () -> BoundedIoSupport.readFully(null, 1));
        assertThrows(IllegalArgumentException.class, () -> BoundedIoSupport.readFully(new ByteArrayInputStream(new byte[0]), 0));
        assertThrows(IllegalArgumentException.class, () -> BoundedIoSupport.readPrefix(null, 1));
        assertThrows(IllegalArgumentException.class, () -> BoundedIoSupport.readPrefix(new ByteArrayInputStream(new byte[0]), -1));
    }
}
