package de.robv.android.xposed;

import java.lang.reflect.Member;

/**
 * Runtime stand-in for the Xposed API type of the same name. TEST RUNTIME ONLY.
 *
 * <p>The published {@code de.robv.android.xposed:api:82} artifact is a compile-time stub whose
 * every method body is {@code throw new RuntimeException("Stub!")}. The shim compiles against that
 * jar, but nothing can execute through it, so a second runtime needs a real implementation of the
 * handful of members {@code AudioRecordHook} and {@code EchidnaModule} actually call.
 *
 * <p>Signatures here must match the published ones exactly. They are not checked by the compiler
 * for the production classes -- those are compiled against the real jar -- so a drift shows up at
 * test runtime as {@code NoSuchMethodError}, which is the intended alarm.
 */
public abstract class XC_MethodHook {

    public static final int PRIORITY_DEFAULT = 50;

    public final int priority;

    public XC_MethodHook() {
        this(PRIORITY_DEFAULT);
    }

    public XC_MethodHook(int priority) {
        this.priority = priority;
    }

    protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
    }

    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
    }

    /**
     * Only the binary name matters: it is the declared return type of the hook-installation
     * methods, so the JVM must be able to resolve it when those descriptors are linked.
     */
    public class Unhook {
        public XC_MethodHook getCallback() {
            return XC_MethodHook.this;
        }

        public void unhook() {
        }
    }

    /**
     * The real one carries mutable call state; the stub's constructor throws before any of it can
     * be set. This mirrors the documented semantics: {@code setResult} clears any pending
     * throwable and vice versa.
     */
    public static final class MethodHookParam {
        public Object[] args;
        public Member method;
        public Object thisObject;

        private Object result;
        private Throwable throwable;

        // Package-private in the published API too, so tests must allocate it reflectively —
        // exactly as they would have to against the real jar.
        MethodHookParam() {
        }

        public Object getResult() {
            return result;
        }

        public void setResult(Object result) {
            this.result = result;
            this.throwable = null;
        }

        public Throwable getThrowable() {
            return throwable;
        }

        public boolean hasThrowable() {
            return throwable != null;
        }

        public void setThrowable(Throwable throwable) {
            this.throwable = throwable;
            this.result = null;
        }

        public Object getResultOrThrowable() throws Throwable {
            if (throwable != null) {
                throw throwable;
            }
            return result;
        }
    }
}
