package com.echidna.lsposed.core;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.Test;

/**
 * Source-level contract: {@link ShimLog} is the only place allowed to call {@code XposedBridge.log}.
 *
 * <p>{@code de.robv.android.xposed} is supplied by the Xposed runtime alone. A direct call inside a
 * failure handler therefore throws {@link NoClassDefFoundError} on exactly the hosts that handler
 * exists to serve, and because the throw happens inside the {@code catch} block there is nothing
 * left to contain it. Routing every diagnostic through {@link ShimLog} keeps one seam that degrades
 * to logcat instead of many sites each needing a private judgement about runtime availability.
 */
public final class ShimLogRoutingContractTest {

    private static final String SHIM_LOG_FILE = "ShimLog.java";

    @Test
    public void shimLogIsTheOnlyDirectCallerOfXposedBridgeLog() throws IOException {
        List<String> offenders = new ArrayList<>();
        for (Path source : mainJavaSources()) {
            if (SHIM_LOG_FILE.equals(source.getFileName().toString())) {
                continue;
            }
            List<String> lines = Files.readAllLines(source, StandardCharsets.UTF_8);
            for (int i = 0; i < lines.size(); i++) {
                if (lines.get(i).contains("XposedBridge.log")) {
                    offenders.add(source.getFileName() + ":" + (i + 1) + ": " + lines.get(i).trim());
                }
            }
        }

        if (!offenders.isEmpty()) {
            fail("XposedBridge.log must be reached through ShimLog.log so it degrades to logcat "
                    + "when the Xposed runtime is absent. Offending call sites:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    @Test
    public void shimLogItselfStillPrefersTheXposedSinkAndKeepsALogcatFallback() throws IOException {
        String shimLog = String.join("\n", Files.readAllLines(
                mainJavaRoot().resolve("com/echidna/lsposed/core/" + SHIM_LOG_FILE),
                StandardCharsets.UTF_8));

        // Guards against "fixing" the contract above by deleting the Xposed path outright, which
        // would silently drop every shim diagnostic out of the LSPosed log on a real device.
        assertTrue("ShimLog must still emit through XposedBridge first",
                shimLog.contains("XposedBridge.log(message)"));
        assertTrue("ShimLog must retain its logcat fallback",
                shimLog.contains("Log.w(FALLBACK_TAG, message)"));
    }

    @Test
    public void theScanActuallyReachesTheShimSourcesItClaimsToCheck() throws IOException {
        List<Path> sources = mainJavaSources();

        // A contract test that silently scans an empty directory passes forever. Anchor it to
        // files that must exist, so a moved source root fails loudly instead of going quiet.
        assertTrue("expected the scan to find the shim sources", sources.size() >= 10);
        List<String> names = sources.stream()
                .map(path -> path.getFileName().toString())
                .collect(Collectors.toList());
        assertTrue(names.contains(SHIM_LOG_FILE));
        assertTrue(names.contains("NativeBridge.java"));
        assertTrue(names.contains("ModuleState.java"));
        assertTrue(names.contains("EchidnaModule.java"));
        assertTrue(names.contains("AudioRecordHook.java"));
        assertFalse("the scan must not reach test sources",
                sources.stream().anyMatch(path -> path.toString().replace('\\', '/')
                        .contains("/src/test/")));
    }

    private static List<Path> mainJavaSources() throws IOException {
        try (Stream<Path> tree = Files.walk(mainJavaRoot())) {
            return tree.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".java"))
                    .collect(Collectors.toList());
        }
    }

    /** Resolves {@code shim/src/main/java} from the test working directory, then by walking up. */
    private static Path mainJavaRoot() {
        Path candidate = Paths.get("src", "main", "java").toAbsolutePath();
        if (Files.isDirectory(candidate)) {
            return candidate;
        }
        for (Path directory = Paths.get("").toAbsolutePath();
                directory != null;
                directory = directory.getParent()) {
            Path shim = directory.resolve(
                    Paths.get("android", "lsposed-shim", "shim", "src", "main", "java"));
            if (Files.isDirectory(shim)) {
                return shim;
            }
        }
        throw new UncheckedIOException(new IOException(
                "cannot locate shim/src/main/java from " + Paths.get("").toAbsolutePath()));
    }
}
