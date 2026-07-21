package com.echidna.lsposed.core;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Builds a {@link ModuleState} over a controllable native layer.
 *
 * <p>Lives in this package because {@code ModuleState}'s constructor and its
 * {@code NativeController} seam are package-private. Hook tests need it because the real singleton
 * is wired to {@link NativeBridge}, whose {@code isEngineReady()} is permanently false off-device —
 * which would make every read callback refuse its permit before reaching any of the logic under
 * test.
 */
public final class TestModuleStates {

    private TestModuleStates() {
    }

    public static final class RecordingController {
        public volatile boolean engineReady = true;
        public volatile boolean bypass;
        public volatile String profile = "";
        /** Counts permit checks, which is how a test observes how often a transform was attempted. */
        public final AtomicInteger engineReadyChecks = new AtomicInteger();
    }

    /** {@code ProfileSnapshotStore.update/resetForTests} are package-private; hook tests need them. */
    public static void publishPolicy(String json) {
        ProfileSnapshotStore.getInstance().update(ProfileSnapshot.parse(json));
    }

    public static void resetStore() {
        ProfileSnapshotStore.getInstance().resetForTests();
    }

    public static ModuleState withController(RecordingController controller) {
        return new ModuleState(
                ProfileSnapshotStore.getInstance(),
                new ModuleState.NativeController() {
                    @Override
                    public boolean initialize() {
                        return true;
                    }

                    @Override
                    public boolean isEngineReady() {
                        controller.engineReadyChecks.incrementAndGet();
                        return controller.engineReady;
                    }

                    @Override
                    public void setBypass(boolean bypass) {
                        controller.bypass = bypass;
                    }

                    @Override
                    public void setProfile(String profile) {
                        controller.profile = profile;
                    }

                    @Override
                    public EchidnaStatus getStatus() {
                        return EchidnaStatus.HOOKED;
                    }
                },
                false);
    }
}
