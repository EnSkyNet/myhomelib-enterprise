package com.myhomelibcorp.shared.util;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ThrowableCycleSafetyTest {
    @Test void nullDefaultMessage() { assertEquals("Unknown error", ThrowableMessages.rootMessage(null)); }
    @Test void nullCustomFallback() { assertEquals("missing", ThrowableMessages.rootMessage(null, "missing")); }
    @Test void deepestMessage() { assertEquals("deep", ThrowableMessages.rootMessage(new Exception("outer", new Exception("deep")))); }
    @Test void blankDeepestUsesCallerFallback() { assertEquals("fallback", ThrowableMessages.rootMessage(new Exception("outer", new Exception("  ")), "fallback")); }
    @Test void twoCauseCycleStops() {
        Exception a = new Exception("a"), b = new Exception("b");
        a.initCause(b); b.initCause(a);
        assertEquals("b", ThrowableMessages.rootMessage(a));
        assertEquals("b", ThrowableText.rootMessage(a, "missing"));
    }
    @Test void threeCauseCycleStops() {
        Exception a=new Exception("a"),b=new Exception("b"),c=new Exception("c");
        a.initCause(b);b.initCause(c);c.initCause(a);
        assertEquals("c", ThrowableMessages.rootMessage(a));
    }
    @Test void selfCauseDoesNotHang() {
        Exception e = new Exception("self") { @Override public synchronized Throwable getCause() { return this; } };
        assertEquals("self", ThrowableMessages.rootMessage(e));
    }
    @Test void veryDeepAcyclicChainPreservesLastCause() {
        Throwable e = new Exception("last");
        for(int i=0;i<400;i++) e=new Exception("wrapper",e);
        assertEquals("last", ThrowableMessages.rootMessage(e));
    }
    @Test void throwableTextNullFallback() {
        assertEquals("", ThrowableText.rootMessage(null,null));
        assertEquals("unknown", ThrowableText.rootMessage(null,"unknown"));
    }
    @Test void throwableTextBlankDeepestUsesClassName() {
        assertEquals("IllegalStateException", ThrowableText.rootMessage(new Exception("outer", new IllegalStateException("")), "fallback"));
    }
    @Test void anyCauseFindsTailMatchInAcyclicChain() {
        Throwable error = new IllegalStateException("outer", new IllegalArgumentException("leaf"));
        assertTrue(ThrowableMessages.anyCause(error, IllegalArgumentException.class::isInstance));
        assertFalse(ThrowableMessages.anyCause(error, java.io.IOException.class::isInstance));
    }
    @Test void anyCauseHandlesNullInput() {
        assertFalse(ThrowableMessages.anyCause(null, cause -> true));
    }
    @Test void anyCauseHandlesSelfLoop() {
        Throwable error = new IllegalStateException("self") {
            @Override public synchronized Throwable getCause() { return this; }
        };
        assertTrue(ThrowableMessages.anyCause(error, IllegalStateException.class::isInstance));
        assertFalse(ThrowableMessages.anyCause(error, java.io.IOException.class::isInstance));
    }
    @Test void anyCauseFindsMatchInTwoNodeCycle() {
        Exception a = new Exception("first"), b = new Exception("database is locked");
        a.initCause(b); b.initCause(a);
        assertTrue(ThrowableMessages.anyCause(a, cause -> "database is locked".equals(cause.getMessage())));
        assertFalse(ThrowableMessages.anyCause(a, IllegalArgumentException.class::isInstance));
    }
    @Test void anyCauseFindsEntryInCycleWithPrefix() {
        Exception first=new Exception("prefix"), second=new Exception("prefix2");
        Exception entry=new Exception("cycle-entry"), c1=new Exception("c1"), c2=new Exception("c2");
        first.initCause(second);second.initCause(entry);entry.initCause(c1);c1.initCause(c2);c2.initCause(entry);
        assertTrue(ThrowableMessages.anyCause(first, cause -> "prefix2".equals(cause.getMessage())));
        assertTrue(ThrowableMessages.anyCause(first, cause -> "c2".equals(cause.getMessage())));
        assertFalse(ThrowableMessages.anyCause(first, cause -> "missing".equals(cause.getMessage())));
    }
    @Test void anyCauseFindsPreviouslyUnvisitedCycleNodeAfterFloydCollision() {
        Exception first=new Exception("a"), second=new Exception("b"), entry=new Exception("entry");
        Exception c1=new Exception("c1"), c2=new Exception("target"), c3=new Exception("c3");
        first.initCause(second);second.initCause(entry);entry.initCause(c1);
        c1.initCause(c2);c2.initCause(c3);c3.initCause(entry);
        assertTrue(ThrowableMessages.anyCause(first, cause -> "target".equals(cause.getMessage())));
    }
    @Test void anyCauseHandlesHundredsOfNestedWrappersWithoutTruncation() {
        Throwable error = new IllegalArgumentException("tail");
        for(int i=0;i<500;i++) error=new IllegalStateException("wrapper",error);
        assertTrue(ThrowableMessages.anyCause(error, IllegalArgumentException.class::isInstance));
    }
}
