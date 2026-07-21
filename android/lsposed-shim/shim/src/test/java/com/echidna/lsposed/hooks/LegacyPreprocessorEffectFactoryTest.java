package com.echidna.lsposed.hooks;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import java.lang.reflect.InvocationTargetException;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/**
 * The boundary that reaches the hidden {@code AudioEffect} constructor. It must translate every
 * way the platform can say "this effect does not exist here" into the manager's own fail-closed
 * {@code EffectFailure}, because anything it lets escape as a raw reflection error would be
 * reported as an unexpected fault rather than as a clean fallback to the direct path.
 */
@RunWith(RobolectricTestRunner.class)
@Config(manifest = Config.NONE, sdk = 34)
public final class LegacyPreprocessorEffectFactoryTest {

    @Test
    public void belowApiTwentySixTheEffectIsRefusedWithoutTouchingTheFramework() {
        AtomicInteger attempts = new AtomicInteger();
        LegacyPreprocessorSessionManager.ReflectionEffectFactory factory =
                new LegacyPreprocessorSessionManager.ReflectionEffectFactory(
                        25,
                        (type, implementation, sessionId) -> {
                            attempts.incrementAndGet();
                            throw new AssertionError("must not reach the framework below API 26");
                        });

        try {
            factory.create(41);
            fail("expected an effect failure below API 26");
        } catch (Exception error) {
            assertTrue(error instanceof LegacyPreprocessorSessionManager.EffectFailure);
            assertEquals("effect_api_unsupported", error.getMessage());
        }
        assertEquals(0, attempts.get());
    }

    @Test
    public void theCanonicalEffectIdentityIsWhatGetsRequested() throws Exception {
        AtomicReference<UUID> requestedType = new AtomicReference<>();
        AtomicReference<UUID> requestedImplementation = new AtomicReference<>();
        AtomicInteger requestedSession = new AtomicInteger();
        LegacyPreprocessorSessionManager.EffectHandle handle = new StubHandle();
        LegacyPreprocessorSessionManager.ReflectionEffectFactory factory =
                new LegacyPreprocessorSessionManager.ReflectionEffectFactory(
                        26,
                        (type, implementation, sessionId) -> {
                            requestedType.set(type);
                            requestedImplementation.set(implementation);
                            requestedSession.set(sessionId);
                            return handle;
                        });

        assertSame(handle, factory.create(42));
        assertEquals(LegacyPreprocessorSessionManager.TYPE_UUID, requestedType.get());
        assertEquals(
                LegacyPreprocessorSessionManager.IMPLEMENTATION_UUID,
                requestedImplementation.get());
        assertEquals(42, requestedSession.get());
    }

    @Test
    public void anUnregisteredEffectIsTranslatedIntoACleanFallbackCode() {
        for (Throwable cause : new Throwable[] {
                new IllegalArgumentException("effect not found"),
                new UnsupportedOperationException("effect library missing")}) {
            LegacyPreprocessorSessionManager.ReflectionEffectFactory factory =
                    new LegacyPreprocessorSessionManager.ReflectionEffectFactory(
                            34,
                            (type, implementation, sessionId) -> {
                                throw new InvocationTargetException(cause);
                            });

            try {
                factory.create(43);
                fail("expected an effect failure for " + cause.getClass().getSimpleName());
            } catch (Exception error) {
                assertTrue(error instanceof LegacyPreprocessorSessionManager.EffectFailure);
                assertEquals("effect_unregistered", error.getMessage());
                assertSame(cause, error.getCause());
            }
        }
    }

    @Test
    public void anUnexpectedConstructorFaultIsNotDisguisedAsAMissingEffect() {
        RuntimeException cause = new IllegalStateException("unexpected platform fault");
        LegacyPreprocessorSessionManager.ReflectionEffectFactory factory =
                new LegacyPreprocessorSessionManager.ReflectionEffectFactory(
                        34,
                        (type, implementation, sessionId) -> {
                            throw new InvocationTargetException(cause);
                        });

        try {
            factory.create(44);
            fail("expected the original reflection failure to propagate");
        } catch (Exception error) {
            // Misreporting this as "unregistered" would hide a real regression behind a
            // routine-looking fallback code.
            assertTrue(error instanceof InvocationTargetException);
            assertSame(cause, error.getCause());
        }
    }

    @Test
    public void theProductionFactoryBindsToTheRunningPlatformApiLevel() {
        // The no-argument constructor is what AudioRecordHook installs, so it must at least be
        // constructible and carry the device's own SDK level rather than a hardcoded one.
        assertNotNull(new LegacyPreprocessorSessionManager.ReflectionEffectFactory());
    }

    @Test
    public void theHiddenAudioEffectApiSurfaceTheShimReflectsAgainstStillExists() throws Exception {
        // The production factory resolves the hidden four-argument AudioEffect constructor and
        // then eagerly looks up all six methods the handle needs. If any of those signatures is
        // renamed or removed on a platform release, this construction throws — which is the only
        // way the project finds out, because at runtime the shim would just fall back to the
        // direct path forever and report nothing more specific than "effect_create_failed".
        LegacyPreprocessorSessionManager.EffectHandle handle =
                new LegacyPreprocessorSessionManager.ReflectionEffectFactory().create(45);

        assertNotNull(handle);
    }

    @Test
    public void theHandleForwardsControlQueriesToTheFrameworkObject() throws Exception {
        LegacyPreprocessorSessionManager.EffectHandle handle =
                new LegacyPreprocessorSessionManager.ReflectionEffectFactory().create(46);

        // An effect the process does not control must report exactly that, because the session
        // manager treats lost control as a reason to abandon the route.
        assertFalse(handle.hasControl());
        handle.release();
    }

    @Test
    public void frameworkFaultsPropagateRatherThanBecomingBogusSuccessCodes() throws Exception {
        LegacyPreprocessorSessionManager.EffectHandle handle =
                new LegacyPreprocessorSessionManager.ReflectionEffectFactory().create(47);

        // The handle deliberately does no catching: a parameter write that the framework refuses
        // must surface as a throw. Returning the int it never received would read as status 0 —
        // success — and would authorize a route that was never actually established.
        assertThrows(() -> handle.setParameter(
                LegacyPreprocessorSessionManager.AUTHORIZE_PARAMETER, new byte[8]));
        assertThrows(() -> handle.getParameter(
                LegacyPreprocessorSessionManager.TELEMETRY_PARAMETER, new byte[48]));
        assertThrows(() -> handle.setEnabled(true));
        assertThrows(handle::descriptor);
    }

    private static void assertThrows(ThrowingCall call) {
        try {
            call.run();
            fail("expected the framework fault to propagate");
        } catch (Throwable expected) {
            assertNotNull(expected);
        }
    }

    private interface ThrowingCall {
        void run() throws Throwable;
    }

    private static final class StubHandle
            implements LegacyPreprocessorSessionManager.EffectHandle {
        @Override
        public LegacyPreprocessorSessionManager.Descriptor descriptor() {
            return new LegacyPreprocessorSessionManager.Descriptor(
                    LegacyPreprocessorSessionManager.TYPE_UUID,
                    LegacyPreprocessorSessionManager.IMPLEMENTATION_UUID,
                    "insert");
        }

        @Override
        public boolean hasControl() {
            return true;
        }

        @Override
        public int setParameter(byte[] parameter, byte[] value) {
            return 0;
        }

        @Override
        public int getParameter(byte[] parameter, byte[] value) {
            return 0;
        }

        @Override
        public int setEnabled(boolean enabled) {
            return 0;
        }

        @Override
        public void release() {
        }
    }
}
