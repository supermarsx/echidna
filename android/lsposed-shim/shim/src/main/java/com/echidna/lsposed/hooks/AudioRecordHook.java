package com.echidna.lsposed.hooks;

import android.media.AudioRecord;
import android.os.Build;

import com.echidna.lsposed.core.AudioFormatUtils;
import com.echidna.lsposed.core.ModuleState;
import com.echidna.lsposed.core.NativeBridge;

import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Installs hooks for AudioRecord.read overloads and forwards captured buffers into the
 * native Echidna pipeline via JNI.
 */
public final class AudioRecordHook {

    private static final String TAG = "EchidnaAudioRecord";
    private static final AtomicBoolean INSTALLED = new AtomicBoolean(false);
    private static final AtomicBoolean HOOK_FAILURE_LOGGED = new AtomicBoolean(false);

    private AudioRecordHook() {
    }

    public static void install(ClassLoader classLoader, ModuleState moduleState) {
        if (!INSTALLED.compareAndSet(false, true)) {
            return;
        }
        try {
            Class<?> audioRecordClass = XposedHelpers.findClass("android.media.AudioRecord", classLoader);
            hookByteArray(audioRecordClass, moduleState);
            hookByteArrayWithMode(audioRecordClass, moduleState);
            hookShortArray(audioRecordClass, moduleState);
            hookShortArrayWithMode(audioRecordClass, moduleState);
            hookFloatArray(audioRecordClass, moduleState);
            hookByteBuffer(audioRecordClass, moduleState);
        } catch (XposedHelpers.ClassNotFoundError error) {
            INSTALLED.set(false);
            XposedBridge.log(TAG + ": unable to resolve AudioRecord class: " + error.getMessage());
        } catch (Throwable throwable) {
            INSTALLED.set(false);
            XposedBridge.log(TAG + ": unable to install AudioRecord hooks: " + throwable);
        }
    }

    private static void hookByteArray(Class<?> audioRecordClass, ModuleState state) {
        try {
            XposedHelpers.findAndHookMethod(
                    audioRecordClass,
                    "read",
                    byte[].class,
                    int.class,
                    int.class,
                    new ByteArrayReadHook(state));
        } catch (NoSuchMethodError ignored) {
            XposedBridge.log(TAG + ": byte[] read(offset,size) missing");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": byte[] read(offset,size) hook failed: " + throwable);
        }
    }

    private static void hookByteArrayWithMode(Class<?> audioRecordClass, ModuleState state) {
        try {
            XposedHelpers.findAndHookMethod(
                    audioRecordClass,
                    "read",
                    byte[].class,
                    int.class,
                    int.class,
                    int.class,
                    new ByteArrayReadHook(state));
        } catch (NoSuchMethodError ignored) {
            // Available on API 23+.
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": byte[] read(offset,size,mode) hook failed: " + throwable);
        }
    }

    private static void hookShortArray(Class<?> audioRecordClass, ModuleState state) {
        try {
            XposedHelpers.findAndHookMethod(
                    audioRecordClass,
                    "read",
                    short[].class,
                    int.class,
                    int.class,
                    new ShortArrayReadHook(state));
        } catch (NoSuchMethodError ignored) {
            XposedBridge.log(TAG + ": short[] read offset/size missing");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": short[] read(offset,size) hook failed: " + throwable);
        }
    }

    private static void hookShortArrayWithMode(Class<?> audioRecordClass, ModuleState state) {
        try {
            XposedHelpers.findAndHookMethod(
                    audioRecordClass,
                    "read",
                    short[].class,
                    int.class,
                    int.class,
                    int.class,
                    new ShortArrayReadHook(state));
        } catch (NoSuchMethodError ignored) {
            // Available on API 23+.
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": short[] read(offset,size,mode) hook failed: " + throwable);
        }
    }

    private static void hookFloatArray(Class<?> audioRecordClass, ModuleState state) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return;
        }
        try {
            XposedHelpers.findAndHookMethod(
                    audioRecordClass,
                    "read",
                    float[].class,
                    int.class,
                    int.class,
                    int.class,
                    new FloatArrayReadHook(state));
        } catch (NoSuchMethodError ignored) {
            XposedBridge.log(TAG + ": float[] read overload missing");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": float[] read hook failed: " + throwable);
        }
    }

    private static void hookByteBuffer(Class<?> audioRecordClass, ModuleState state) {
        try {
            XposedHelpers.findAndHookMethod(
                    audioRecordClass,
                    "read",
                    ByteBuffer.class,
                    int.class,
                    new ByteBufferReadHook(state));
        } catch (NoSuchMethodError ignored) {
            XposedBridge.log(TAG + ": ByteBuffer read(size) missing");
        } catch (Throwable throwable) {
            XposedBridge.log(TAG + ": ByteBuffer read(size) hook failed: " + throwable);
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                XposedHelpers.findAndHookMethod(
                        audioRecordClass,
                        "read",
                        ByteBuffer.class,
                        int.class,
                        int.class,
                        new ByteBufferReadHook(state));
            } catch (NoSuchMethodError ignored) {
                // Optional overload introduced on newer APIs.
            } catch (Throwable throwable) {
                XposedBridge.log(TAG + ": ByteBuffer read(size,mode) hook failed: " + throwable);
            }
        }
    }

    private abstract static class AudioRecordReadHook extends XC_MethodHook implements
            AudioReadTransaction.PermitGate,
            AudioReadTransaction.Transform,
            AudioReadTransaction.CommitObserver,
            AudioReadTransaction.FailureObserver {

        final ModuleState state;

        AudioRecordReadHook(ModuleState state) {
            this.state = state;
        }

        @Override
        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
            try {
                afterHookedMethodSafely(param);
            } catch (Throwable throwable) {
                state.setBypassOverride(true);
                logHookFailure("AudioRecord callback failed; native processing disabled", throwable);
            }
        }

        private void afterHookedMethodSafely(MethodHookParam param) {
            Object result = param.getResult();
            if (!(result instanceof Integer)) {
                return;
            }
            int samplesOrBytes = (Integer) result;
            if (samplesOrBytes <= 0) {
                return;
            }
            Object buffer = param.args != null && param.args.length > 0 ? param.args[0] : null;
            int offset = buffer instanceof ByteBuffer
                    ? 0
                    : param.args != null && param.args.length > 1 && param.args[1] instanceof Integer
                            ? (Integer) param.args[1]
                            : -1;
            int preservedResult = AudioReadTransaction.execute(
                    samplesOrBytes,
                    buffer,
                    offset,
                    param,
                    this,
                    this,
                    this,
                    this);
            // Always preserve AudioRecord's actual return value, including partial reads.
            param.setResult(preservedResult);
        }

        @Override
        public long begin() {
            return state.beginAudioProcessing();
        }

        @Override
        public boolean isCurrent(long permit) {
            return state.isAudioProcessingPermitCurrent(permit);
        }

        @Override
        public int apply(Object callbackContext, int returnedUnits) {
            if (!(callbackContext instanceof MethodHookParam)) {
                return 0;
            }
            BufferContext context = BufferContext.from((MethodHookParam) callbackContext);
            if (!context.isValid(returnedUnits)) {
                return 0;
            }
            int frames = computeFrames(context, returnedUnits);
            if (frames <= 0) {
                return 0;
            }
            return process(context, frames, returnedUnits) ? frames : 0;
        }

        @Override
        public void onCommit(int frames) {
            state.noteProcessingSuccess(frames);
        }

        @Override
        public void onFailure(Throwable throwable) {
            state.setBypassOverride(true);
            logHookFailure("AudioRecord callback failed; native processing disabled", throwable);
        }

        abstract int computeFrames(BufferContext context, int resultUnits);

        abstract boolean process(BufferContext context, int frames, int resultUnits);
    }

    private static final class BufferContext {
        final AudioRecord record;
        final Object[] args;
        final int sampleRate;
        final int channelCount;
        final int encoding;

        private BufferContext(AudioRecord record, Object[] args, int sampleRate, int channelCount, int encoding) {
            this.record = record;
            this.args = args;
            this.sampleRate = sampleRate;
            this.channelCount = channelCount;
            this.encoding = encoding;
        }

        static BufferContext from(XC_MethodHook.MethodHookParam param) {
            if (!(param.thisObject instanceof AudioRecord)) {
                return invalid(param.args);
            }
            AudioRecord record = (AudioRecord) param.thisObject;
            try {
                int sampleRate = record.getSampleRate();
                int channelCount = record.getChannelCount();
                int encoding = record.getAudioFormat();
                return new BufferContext(record, param.args, sampleRate, channelCount, encoding);
            } catch (Throwable throwable) {
                logHookFailure("AudioRecord metadata read failed", throwable);
                return invalid(param.args);
            }
        }

        static BufferContext invalid(Object[] args) {
            return new BufferContext(null, args, 0, 0, 0);
        }

        boolean isValid(int resultUnits) {
            if (sampleRate <= 0 || channelCount <= 0 || resultUnits <= 0 || args == null ||
                    args.length == 0 || args[0] == null) {
                return false;
            }
            Object buffer = args[0];
            if (buffer instanceof byte[]) {
                return hasArrayRange(((byte[]) buffer).length, resultUnits);
            }
            if (buffer instanceof short[]) {
                return hasArrayRange(((short[]) buffer).length, resultUnits);
            }
            if (buffer instanceof float[]) {
                return hasArrayRange(((float[]) buffer).length, resultUnits);
            }
            if (buffer instanceof ByteBuffer) {
                ByteBuffer byteBuffer = (ByteBuffer) buffer;
                // AudioRecord accepts direct buffers only and writes from index zero while
                // intentionally ignoring position and limit.
                return byteBuffer.isDirect()
                        && !byteBuffer.isReadOnly()
                        && resultUnits <= byteBuffer.capacity();
            }
            return false;
        }

        private boolean hasArrayRange(int length, int resultUnits) {
            if (args.length < 3 || !(args[1] instanceof Integer)) {
                return false;
            }
            int offset = (Integer) args[1];
            return offset >= 0 && (long) offset + (long) resultUnits <= (long) length;
        }

        int framesFromBytes(int bytes) {
            int bps = AudioFormatUtils.bytesPerSample(encoding);
            if (bps <= 0) {
                return 0;
            }
            long denominator = (long) bps * (long) Math.max(channelCount, 1);
            if (denominator <= 0) {
                return 0;
            }
            return (int) Math.min((long) Integer.MAX_VALUE, (long) bytes / denominator);
        }

        int framesFromSamples(int samples) {
            return samples / Math.max(channelCount, 1);
        }
    }

    private static final class ByteArrayReadHook extends AudioRecordReadHook {

        ByteArrayReadHook(ModuleState state) {
            super(state);
        }

        @Override
        int computeFrames(BufferContext context, int resultUnits) {
            return context.framesFromBytes(resultUnits);
        }

        @Override
        boolean process(BufferContext context, int frames, int resultUnits) {
            byte[] buffer = (byte[]) context.args[0];
            int offset = (Integer) context.args[1];
            boolean success = NativeBridge.processByteArray(
                    buffer,
                    offset,
                    resultUnits,
                    context.encoding,
                    context.sampleRate,
                    context.channelCount);
            if (!success) {
                XposedBridge.log(TAG + ": native processing failed for byte[] buffer");
            }
            return success;
        }
    }

    private static final class ShortArrayReadHook extends AudioRecordReadHook {

        ShortArrayReadHook(ModuleState state) {
            super(state);
        }

        @Override
        int computeFrames(BufferContext context, int resultUnits) {
            return context.framesFromSamples(resultUnits);
        }

        @Override
        boolean process(BufferContext context, int frames, int resultUnits) {
            short[] buffer = (short[]) context.args[0];
            int offset = (Integer) context.args[1];
            boolean success = NativeBridge.processShortArray(
                    buffer,
                    offset,
                    resultUnits,
                    context.sampleRate,
                    context.channelCount);
            if (!success) {
                XposedBridge.log(TAG + ": native processing failed for short[] buffer");
            }
            return success;
        }
    }

    private static final class FloatArrayReadHook extends AudioRecordReadHook {

        FloatArrayReadHook(ModuleState state) {
            super(state);
        }

        @Override
        int computeFrames(BufferContext context, int resultUnits) {
            return context.framesFromSamples(resultUnits);
        }

        @Override
        boolean process(BufferContext context, int frames, int resultUnits) {
            float[] buffer = (float[]) context.args[0];
            int offset = (Integer) context.args[1];
            boolean success = NativeBridge.processFloatArray(
                    buffer,
                    offset,
                    resultUnits,
                    context.sampleRate,
                    context.channelCount);
            if (!success) {
                XposedBridge.log(TAG + ": native processing failed for float[] buffer");
            }
            return success;
        }
    }

    private static final class ByteBufferReadHook extends AudioRecordReadHook {

        ByteBufferReadHook(ModuleState state) {
            super(state);
        }

        @Override
        int computeFrames(BufferContext context, int resultUnits) {
            return context.framesFromBytes(resultUnits);
        }

        @Override
        boolean process(BufferContext context, int frames, int resultUnits) {
            ByteBuffer buffer = (ByteBuffer) context.args[0];
            boolean success = NativeBridge.processAudioRecordByteBuffer(
                    buffer,
                    resultUnits,
                    context.encoding,
                    context.sampleRate,
                    context.channelCount);
            if (!success) {
                XposedBridge.log(TAG + ": native processing failed for ByteBuffer");
            }
            return success;
        }
    }

    private static void logHookFailure(String message, Throwable throwable) {
        if (HOOK_FAILURE_LOGGED.compareAndSet(false, true)) {
            XposedBridge.log(TAG + ": " + message + ": " + throwable);
        }
    }
}
