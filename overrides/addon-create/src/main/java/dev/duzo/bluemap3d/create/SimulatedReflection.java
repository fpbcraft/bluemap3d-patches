package dev.duzo.bluemap3d.create;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/** Shared reflection boundary for the optional Create: Simulated integration. */
final class SimulatedReflection {

    private SimulatedReflection() {}

    static Class<?> loadClass(String name) throws ClassNotFoundException {
        return Class.forName(name, false, SimulatedReflection.class.getClassLoader());
    }

    static Object invoke(Method method, Object target, Object... args)
            throws ReflectiveOperationException {
        try {
            return method.invoke(target, args);
        } catch (InvocationTargetException error) {
            Throwable cause = error.getCause();
            if (cause instanceof ReflectiveOperationException reflective) {
                throw reflective;
            }
            if (cause instanceof RuntimeException runtime) {
                throw runtime;
            }
            throw error;
        }
    }

    static String rootMessage(Throwable error) {
        Throwable current = error;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return current.getClass().getSimpleName()
                + (message == null || message.isBlank() ? "" : ": " + message);
    }
}
