package io.github.slightneko.notificationguard.hook;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.InvocationTargetException;

final class Reflect {
    static Field field(Class<?> cls, String name) throws NoSuchFieldException {
        for (Class<?> c = cls; c != null; c = c.getSuperclass()) {
            try { Field f = c.getDeclaredField(name); f.setAccessible(true); return f; }
            catch (NoSuchFieldException ignored) { }
        }
        throw new NoSuchFieldException(name);
    }
    static Object get(Object target, String name) throws Exception { return field(target.getClass(), name).get(target); }
    static Object callStatic(Class<?> type, String name, Object... args) throws Exception { return invoke(type, null, name, args); }
    static Object call(Object target, String name, Object... args) throws Exception { return invoke(target.getClass(), target, name, args); }
    private static Object invoke(Class<?> type, Object target, String name, Object... args) throws Exception {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method m : c.getDeclaredMethods()) {
                if (!m.getName().equals(name) || m.getParameterCount() != args.length) continue;
                Class<?>[] p = m.getParameterTypes(); boolean matches = true;
                for (int i = 0; i < p.length; i++) {
                    Class<?> type = p[i].isPrimitive() ? boxed(p[i]) : p[i];
                    if (args[i] != null && !type.isInstance(args[i])) { matches = false; break; }
                }
                if (!matches) continue;
                m.setAccessible(true);
                try { return m.invoke(target, args); }
                catch (InvocationTargetException e) { throw new IllegalStateException("Invocation failed: " + name, e.getCause()); }
            }
        }
        throw new NoSuchMethodException(name);
    }
    private static Class<?> boxed(Class<?> c) {
        if (c == int.class) return Integer.class; if (c == boolean.class) return Boolean.class;
        if (c == long.class) return Long.class; if (c == float.class) return Float.class;
        if (c == double.class) return Double.class; if (c == byte.class) return Byte.class;
        if (c == short.class) return Short.class; if (c == char.class) return Character.class;
        return c;
    }
}
