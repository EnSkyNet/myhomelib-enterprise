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
}
