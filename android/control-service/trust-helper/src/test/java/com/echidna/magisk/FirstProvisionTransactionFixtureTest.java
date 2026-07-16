package com.echidna.magisk;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

public final class FirstProvisionTransactionFixtureTest {
    private FirstProvisionTransactionFixtureTest() {}

    public static void main(String[] arguments) throws Exception {
        for (int count : new int[] {2, 3, 4}) {
            for (int failAt = 0; failAt < count; failAt++) {
                verifyPostRenameFailureRollsBackEveryOutput(count, failAt);
            }
            verifyPostWriteVerificationFailureRollsBackEveryOutput(count);
        }
        System.out.println("first-provision transactions: rename/fsync/verify rollback PASS");
    }

    private static void verifyPostRenameFailureRollsBackEveryOutput(int count, int failAt)
            throws Exception {
        Path directory = Files.createTempDirectory("echidna-first-provision-");
        String[] paths = new String[count];
        for (int index = 0; index < count; index++) {
            paths[index] = directory.resolve("output-" + index).toString();
        }
        boolean failed = false;
        try {
            FirstProvisionTransaction.create(paths, new FixtureOperations(paths, failAt, false));
        } catch (Exception expected) {
            failed = true;
        }
        require(failed, "injected post-rename fsync failure was not propagated");
        requireAbsent(paths);
        Files.delete(directory);
    }

    private static void verifyPostWriteVerificationFailureRollsBackEveryOutput(int count)
            throws Exception {
        Path directory = Files.createTempDirectory("echidna-first-provision-verify-");
        String[] paths = new String[count];
        for (int index = 0; index < count; index++) {
            paths[index] = directory.resolve("output-" + index).toString();
        }
        boolean failed = false;
        try {
            FirstProvisionTransaction.create(paths, new FixtureOperations(paths, -1, true));
        } catch (Exception expected) {
            failed = true;
        }
        require(failed, "injected post-write verification failure was not propagated");
        requireAbsent(paths);
        Files.delete(directory);
    }

    private static void requireAbsent(String[] paths) {
        for (String value : paths) {
            require(!Files.exists(Paths.get(value)), "rollback left final output: " + value);
            require(
                    !Files.exists(Paths.get(value + ".tmp.fixture")),
                    "rollback left temporary output: " + value);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static final class FixtureOperations implements FirstProvisionTransaction.Operations {
        private final String[] paths;
        private final int failAt;
        private final boolean failVerification;

        FixtureOperations(String[] paths, int failAt, boolean failVerification) {
            this.paths = paths;
            this.failAt = failAt;
            this.failVerification = failVerification;
        }

        @Override
        public void write(int index) throws Exception {
            Path finalPath = Paths.get(paths[index]);
            Path temporary = Paths.get(paths[index] + ".tmp.fixture");
            Files.write(temporary, new byte[] {(byte) index});
            Files.move(temporary, finalPath);
            if (index == failAt) {
                throw new Exception("injected parent fsync failure after rename " + index);
            }
        }

        @Override
        public void verify() throws Exception {
            if (failVerification) {
                throw new Exception("injected post-write verification failure");
            }
        }

        @Override
        public void remove(String path) throws Exception {
            Files.deleteIfExists(Paths.get(path));
            Files.deleteIfExists(Paths.get(path + ".tmp.fixture"));
        }
    }
}
