package com.echidna.magisk;

/** Rolls back every known-new output when first-provision writes fail at any step. */
final class FirstProvisionTransaction {
    interface Operations {
        void write(int index) throws Exception;

        void verify() throws Exception;

        void remove(String path) throws Exception;
    }

    private FirstProvisionTransaction() {}

    static void create(String[] paths, Operations operations) throws Exception {
        if (paths == null || paths.length == 0 || operations == null) {
            throw new IllegalArgumentException("first-provision transaction is empty");
        }
        try {
            for (int index = 0; index < paths.length; index++) {
                if (paths[index] == null || paths[index].isEmpty()) {
                    throw new IllegalArgumentException("first-provision path is empty");
                }
                operations.write(index);
            }
            operations.verify();
        } catch (Exception failure) {
            for (int index = paths.length - 1; index >= 0; index--) {
                try {
                    operations.remove(paths[index]);
                } catch (Exception rollbackFailure) {
                    failure.addSuppressed(rollbackFailure);
                }
            }
            throw failure;
        }
    }
}
