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

    void update(ProfileSnapshot next) {
        snapshot.set(next != null ? next : ProfileSnapshot.empty());
        version.incrementAndGet();
    }

    /** Monotonic generation used by hot audio hooks to notice policy changes cheaply. */
    public long version() {
        return version.get();
    }

    /** Lazily starts the background receiver exactly once per process. */
    public void ensureStarted() {
        if (receiverStarted.compareAndSet(false, true)) {
            new ProfileSyncReceiver(this).start();
        }
    }
}
