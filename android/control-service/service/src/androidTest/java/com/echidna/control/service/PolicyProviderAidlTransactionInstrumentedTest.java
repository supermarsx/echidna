package com.echidna.control.service;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.os.Binder;
import android.os.IBinder;
import android.os.Parcel;
import android.os.RemoteException;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.ArrayList;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class PolicyProviderAidlTransactionInstrumentedTest {
    @Test
    public void transactionNumbersRemainAppendOnlyThroughV7() {
        int first = IBinder.FIRST_CALL_TRANSACTION;
        assertEquals(first, IEchidnaPolicyProvider.Stub.TRANSACTION_getPolicySnapshot);
        assertEquals(first + 1, IEchidnaPolicyProvider.Stub.TRANSACTION_registerListener);
        assertEquals(first + 2, IEchidnaPolicyProvider.Stub.TRANSACTION_unregisterListener);
        assertEquals(first + 3, IEchidnaPolicyProvider.Stub.TRANSACTION_getApiVersion);
        assertEquals(
                first + 4,
                IEchidnaPolicyProvider.Stub.TRANSACTION_requestLegacyPreprocessorCapability);
        assertEquals(
                first + 5,
                IEchidnaPolicyProvider.Stub.TRANSACTION_reportLegacyPreprocessorTelemetry);
        assertEquals(
                first + 6,
                IEchidnaPolicyProvider.Stub.TRANSACTION_reportLegacyPreprocessorTelemetryV4);
        assertEquals(
                first + 7,
                IEchidnaPolicyProvider.Stub.TRANSACTION_reportLegacyPreprocessorTelemetryProofV5);
        assertEquals(
                first + 8,
                IEchidnaPolicyProvider.Stub.TRANSACTION_registerCaptureOwnerClient);
        assertEquals(
                first + 9,
                IEchidnaPolicyProvider.Stub.TRANSACTION_reportCaptureOwnerInactive);
        assertEquals(
                first + 10,
                IEchidnaPolicyProvider.Stub.TRANSACTION_requestLegacyPreprocessorCapabilityV7);
        assertEquals(
                first + 11,
                IEchidnaPolicyProvider.Stub.TRANSACTION_reportLegacyPreprocessorTelemetryV7);
        assertEquals(
                first + 12,
                IEchidnaPolicyProvider.Stub.TRANSACTION_reportLegacyPreprocessorTelemetryProofV7);
        assertEquals(
                first + 13,
                IEchidnaPolicyProvider.Stub.TRANSACTION_reportCaptureOwnerInactiveV7);
    }

    @Test
    public void generatedProxyKeepsLegacyOnewayAndV7Synchronous() throws Exception {
        RecordingBinder remote = new RecordingBinder();
        IEchidnaPolicyProvider provider = IEchidnaPolicyProvider.Stub.asInterface(remote);

        provider.requestLegacyPreprocessorCapability(41, "com.example", 7L, new byte[16], null);
        remote.assertLast(
                IEchidnaPolicyProvider.Stub.TRANSACTION_requestLegacyPreprocessorCapability,
                IBinder.FLAG_ONEWAY,
                true);
        provider.reportLegacyPreprocessorTelemetry(41, "com.example", 7L, new byte[48]);
        remote.assertLast(
                IEchidnaPolicyProvider.Stub.TRANSACTION_reportLegacyPreprocessorTelemetry,
                IBinder.FLAG_ONEWAY,
                true);
        provider.reportLegacyPreprocessorTelemetryV4(
                41, "com.example", 7L, new byte[16], new byte[48]);
        remote.assertLast(
                IEchidnaPolicyProvider.Stub.TRANSACTION_reportLegacyPreprocessorTelemetryV4,
                IBinder.FLAG_ONEWAY,
                true);
        provider.reportLegacyPreprocessorTelemetryProofV5(
                41, "com.example", 7L, new byte[112]);
        remote.assertLast(
                IEchidnaPolicyProvider.Stub.TRANSACTION_reportLegacyPreprocessorTelemetryProofV5,
                IBinder.FLAG_ONEWAY,
                true);
        provider.reportCaptureOwnerInactive("com.example", 7L, 9L);
        remote.assertLast(
                IEchidnaPolicyProvider.Stub.TRANSACTION_reportCaptureOwnerInactive,
                IBinder.FLAG_ONEWAY,
                true);

        assertTrue(provider.requestLegacyPreprocessorCapabilityV7(
                41, "com.example", 7L, new byte[16], null));
        remote.assertLast(
                IEchidnaPolicyProvider.Stub.TRANSACTION_requestLegacyPreprocessorCapabilityV7,
                0,
                false);
        assertTrue(provider.reportLegacyPreprocessorTelemetryV7(
                41, "com.example", 7L, new byte[16], new byte[48]));
        remote.assertLast(
                IEchidnaPolicyProvider.Stub.TRANSACTION_reportLegacyPreprocessorTelemetryV7,
                0,
                false);
        assertTrue(provider.reportLegacyPreprocessorTelemetryProofV7(
                41, "com.example", 7L, new byte[112]));
        remote.assertLast(
                IEchidnaPolicyProvider.Stub.TRANSACTION_reportLegacyPreprocessorTelemetryProofV7,
                0,
                false);
        assertTrue(provider.reportCaptureOwnerInactiveV7("com.example", 7L, 9L));
        remote.assertLast(
                IEchidnaPolicyProvider.Stub.TRANSACTION_reportCaptureOwnerInactiveV7,
                0,
                false);
    }

    private static final class RecordingBinder extends Binder {
        private final List<Call> calls = new ArrayList<>();

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags)
                throws RemoteException {
            calls.add(new Call(code, flags, reply == null));
            if (reply != null) {
                reply.writeNoException();
                reply.writeBoolean(true);
            }
            return true;
        }

        void assertLast(int code, int flags, boolean replyWasNull) {
            assertFalse("proxy did not transact", calls.isEmpty());
            Call call = calls.get(calls.size() - 1);
            assertEquals(code, call.code);
            assertEquals(flags, call.flags);
            assertEquals(replyWasNull, call.replyWasNull);
        }
    }

    private static final class Call {
        final int code;
        final int flags;
        final boolean replyWasNull;

        Call(int code, int flags, boolean replyWasNull) {
            this.code = code;
            this.flags = flags;
            this.replyWasNull = replyWasNull;
        }
    }
}
