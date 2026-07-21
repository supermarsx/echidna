package de.robv.android.xposed;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Runtime stand-in for the Xposed API type of the same name. TEST RUNTIME ONLY.
 *
 * <p>{@link #findAndHookMethod} records the callback per overload signature instead of hooking, and
 * {@link #MISSING_SIGNATURES} lets a test declare an overload absent so the installer's
 * {@code NoSuchMethodError} degradation can be exercised — that is a real device condition, since
 * several AudioRecord read overloads only exist from API 23 onwards.
 */
public final class XposedHelpers {

    /** One entry per installed read hook: {declaringClass, signature, callback}. */
    public static final List<Object[]> RECORDED_METHOD_HOOKS =
            Collections.synchronizedList(new ArrayList<>());

    /** Signatures a test has declared absent, in {@link #signatureOf} form. */
    public static final Set<String> MISSING_SIGNATURES =
            Collections.synchronizedSet(new LinkedHashSet<>());

    private XposedHelpers() {
    }

    public static Class<?> findClass(String className, ClassLoader classLoader) {
        try {
            return Class.forName(
                    className,
                    false,
                    classLoader != null ? classLoader : XposedHelpers.class.getClassLoader());
        } catch (ClassNotFoundException error) {
            throw new ClassNotFoundError(className);
        }
    }

    public static XC_MethodHook.Unhook findAndHookMethod(
            Class<?> hookClass, String methodName, Object... parameterTypesAndCallback) {
        if (parameterTypesAndCallback.length == 0) {
            throw new IllegalArgumentException("no callback supplied");
        }
        Object callback = parameterTypesAndCallback[parameterTypesAndCallback.length - 1];
        Class<?>[] parameterTypes = new Class<?>[parameterTypesAndCallback.length - 1];
        for (int i = 0; i < parameterTypes.length; i++) {
            parameterTypes[i] = (Class<?>) parameterTypesAndCallback[i];
        }
        String signature = signatureOf(methodName, parameterTypes);
        if (MISSING_SIGNATURES.contains(signature)) {
            throw new NoSuchMethodError(signature);
        }
        RECORDED_METHOD_HOOKS.add(new Object[] {hookClass, signature, callback});
        return null;
    }

    /** e.g. {@code read(byte[],int,int)}. Not part of the published API. */
    public static String signatureOf(String methodName, Class<?>... parameterTypes) {
        StringBuilder signature = new StringBuilder(methodName).append('(');
        for (int i = 0; i < parameterTypes.length; i++) {
            if (i > 0) {
                signature.append(',');
            }
            signature.append(parameterTypes[i].getSimpleName());
        }
        return signature.append(')').toString();
    }

    /** Returns the callback installed for {@code signature}, or null. Not published API. */
    public static XC_MethodHook recordedMethodHook(String signature) {
        synchronized (RECORDED_METHOD_HOOKS) {
            for (Object[] entry : RECORDED_METHOD_HOOKS) {
                if (signature.equals(entry[1])) {
                    return (XC_MethodHook) entry[2];
                }
            }
        }
        return null;
    }

    /** Clears recorded state between tests. Not part of the published API. */
    public static void resetRecordingForTests() {
        RECORDED_METHOD_HOOKS.clear();
        MISSING_SIGNATURES.clear();
    }

    public static final class ClassNotFoundError extends Error {
        ClassNotFoundError(String message) {
            super(message);
        }
    }
}
