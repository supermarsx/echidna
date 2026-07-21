package de.robv.android.xposed.callbacks;

/**
 * Runtime stand-in for the Xposed API type of the same name. TEST RUNTIME ONLY.
 *
 * <p>Only the members the shim reads are present. {@code appInfo} is omitted deliberately: it is
 * typed {@code android.content.pm.ApplicationInfo}, the shim never touches it, and leaving it out
 * keeps this stand-in compilable without an android.jar on its classpath.
 */
public abstract class XC_LoadPackage {

    public static final class LoadPackageParam {
        public ClassLoader classLoader;
        public String packageName;
        public String processName;

        // Package-private in the published API too, so tests allocate it reflectively.
        LoadPackageParam() {
        }
    }
}
