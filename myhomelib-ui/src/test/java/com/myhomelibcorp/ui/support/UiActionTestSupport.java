package com.myhomelibcorp.ui.support;

import javafx.application.Platform;
import org.assertj.core.api.Assertions;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.mockito.Mockito.mock;

public final class UiActionTestSupport {
    private UiActionTestSupport() {}

    public static void startFx() throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        try { Platform.startup(started::countDown); }
        catch (IllegalStateException alreadyStarted) { started.countDown(); }
        Assertions.assertThat(started.await(5, TimeUnit.SECONDS)).isTrue();
        Platform.setImplicitExit(false);
    }

    public static <T> Fixture<T> constructWithMocks(Class<T> type) throws Exception {
        Constructor<?> ctor = type.getDeclaredConstructors()[0];
        ctor.setAccessible(true);
        Class<?>[] parameterTypes = ctor.getParameterTypes();
        Object[] args = new Object[parameterTypes.length];
        Map<Class<?>, Object> mocks = new LinkedHashMap<>();
        for (int i = 0; i < parameterTypes.length; i++) {
            Object value = mock(parameterTypes[i]);
            args[i] = value;
            mocks.putIfAbsent(parameterTypes[i], value);
        }
        @SuppressWarnings("unchecked") T instance = (T) ctor.newInstance(args);
        return new Fixture<>(instance, mocks);
    }

    public static void invoke(Object target, String name) throws Exception {
        Method method = findMethod(target.getClass(), name);
        method.setAccessible(true);
        fx(() -> {
            try { method.invoke(target); }
            catch (ReflectiveOperationException e) {
                Throwable cause = e.getCause() == null ? e : e.getCause();
                if (cause instanceof RuntimeException r) throw r;
                if (cause instanceof Error err) throw err;
                throw new RuntimeException(cause);
            }
            return null;
        });
    }

    private static Method findMethod(Class<?> type, String name) throws NoSuchMethodException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) if (m.getName().equals(name) && m.getParameterCount() == 0) return m;
        }
        throw new NoSuchMethodException(type.getName() + "#" + name + "()");
    }

    public static void setField(Object target, String name, Object value) throws Exception {
        Field field = field(target.getClass(), name); field.setAccessible(true); field.set(target, value);
    }

    public static Object getField(Object target, String name) throws Exception {
        Field field = field(target.getClass(), name); field.setAccessible(true); return field.get(target);
    }

    private static Field field(Class<?> type, String name) throws NoSuchFieldException {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            try { return c.getDeclaredField(name); } catch (NoSuchFieldException ignored) {}
        }
        throw new NoSuchFieldException(type.getName() + "." + name);
    }

    public static <T> T fx(Callable<T> action) throws Exception {
        if (Platform.isFxApplicationThread()) return action.call();
        AtomicReference<T> result = new AtomicReference<>();
        AtomicReference<Throwable> error = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        Platform.runLater(() -> {
            try { result.set(action.call()); } catch (Throwable t) { error.set(t); } finally { done.countDown(); }
        });
        Assertions.assertThat(done.await(8, TimeUnit.SECONDS)).isTrue();
        if (error.get() != null) {
            if (error.get() instanceof RuntimeException r) throw r;
            if (error.get() instanceof Error e) throw e;
            throw new RuntimeException(error.get());
        }
        return result.get();
    }

    public record Fixture<T>(T instance, Map<Class<?>, Object> mocks) {
        public <D> D dependency(Class<D> type) { return type.cast(mocks.get(type)); }
    }
}
