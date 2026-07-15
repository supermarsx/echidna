package com.echidna.lsposed.core;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Process-wide holder for the latest policy snapshot published by the control
 * service over the ProfileSyncBridge contract. {@link ProfileSyncReceiver} feeds
 * this store; hook policy is resolved by reading {@link #getSnapshot()}.
 *
 * <p>Replaces the former binder-query path: instead of transacting a (never
 * registered) ServiceManager binder, the shim now consumes the same shared
 * snapshot the native engine consumes. The default value is
 * {@link ProfileSnapshot#empty()}, so before any snapshot arrives — or if the
 * receiver never binds — resolution is fail-closed.
 */
public final class ProfileSnapshotStore {

    private static final ProfileSnapshotStore INSTANCE = new ProfileSnapshotStore();

    private final AtomicReference<ProfileSnapshot> snapshot =
            new AtomicReference<>(ProfileSnapshot.empty());
    private final AtomicLong version = new AtomicLong(0L);
    private final AtomicBoolean receiverStarted = new AtomicBoolean(false);
    private long highestGeneration;
    private String highestGenerationPayload = "";

    private ProfileSnapshotStore() {
    }

    public static ProfileSnapshotStore getInstance() {
        return INSTANCE;
    }

    /** Returns the most recent snapshot, never null (fail-closed default). */
    public ProfileSnapshot getSnapshot() {
        ProfileSnapshot current = snapshot.get();
        return current != null ? current : ProfileSnapshot.empty();
    }

    synchronized void update(ProfileSnapshot next) {
        if (next == null || !next.isValid()) {
            failClosed();
            return;
        }
        long generation = next.generation();
        if (generation < highestGeneration) {
            return;
        }
        if (generation == highestGeneration) {
            if (!highestGenerationPayload.equals(next.rawPayload())) {
                return;
            }
            if (snapshot.get().isValid()) {
                return;
            }
        } else {
            highestGeneration = generation;
            highestGenerationPayload = next.rawPayload();
        }
        snapshot.set(next);
        version.incrementAndGet();
    }

    /** Disables hooks without erasing the rollback/conflict watermark. */
    synchronized void failClosed() {
        if (snapshot.get().isValid()) {
            snapshot.set(ProfileSnapshot.empty());
            version.incrementAndGet();
        }
    }

    /** Monotonic generation used by hot audio hooks to notice policy changes cheaply. */
    public long version() {
        return version.get();
    }

    /** Lazily starts the background receiver exactly once per process. */
    public void ensureStarted(String packageName, String processName) {
        if (receiverStarted.compareAndSet(false, true)) {
            new ProfileSyncReceiver(this, processName).start();
        }
    }

    synchronized void resetForTests() {
        snapshot.set(ProfileSnapshot.empty());
        version.set(0L);
        highestGeneration = 0L;
        highestGenerationPayload = "";
        receiverStarted.set(false);
    }
}
