package com.echidna.lsposed.hooks;

import android.media.AudioRecord;
import android.os.Build;

import com.echidna.lsposed.core.AudioFormatUtils;
import com.echidna.lsposed.core.ModuleState;
import com.echidna.lsposed.core.NativeBridge;

import java.nio.ByteBuffer;
import java.util.Arrays;
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
    private static final byte[] ZERO_BYTES = new byte[4096];

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

    private abstract static class AudioRecordReadHook extends XC_MethodHook {

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
            if (!state.isHookAllowed()) {
                BufferContext context = BufferContext.from(param);
                if (context.isValid(samplesOrBytes)) {
                    zeroBuffer(context, samplesOrBytes);
                }
                param.setResult(0);
                return;
            }
            if (!state.shouldProcessAudio()) {
                return;
            }
            BufferContext context = BufferContext.from(param);
            if (!context.isValid(samplesOrBytes)) {
                return;
            }
            int frames = computeFrames(context, samplesOrBytes);
            if (frames <= 0) {
                return;
            }
            if (process(context, frames, samplesOrBytes)) {
                state.noteProcessingSuccess(frames);
            }
        }

        abstract int computeFrames(BufferContext context, int resultUnits);

        abstract boolean process(BufferContext context, int frames, int resultUnits);

        void zeroBuffer(BufferContext context, int resultUnits) {
            Object buffer = context.args[0];
            if (buffer instanceof byte[]) {
                int offset = (Integer) context.args[1];
                byte[] data = (byte[]) buffer;
                int end = (int) Math.min((long) data.length, (long) offset + (long) resultUnits);
                if (offset >= 0 && offset < end) {
                    Arrays.fill(data, offset, end, (byte) 0);
                }
                return;
            }
            if (buffer instanceof short[]) {
                int offset = (Integer) context.args[1];
                short[] data = (short[]) buffer;
                int end = (int) Math.min((long) data.length, (long) offset + (long) resultUnits);
                if (offset >= 0 && offset < end) {
                    Arrays.fill(data, offset, end, (short) 0);
                }
                return;
            }
            if (buffer instanceof float[]) {
                int offset = (Integer) context.args[1];
                float[] data = (float[]) buffer;
                int end = (int) Math.min((long) data.length, (long) offset + (long) resultUnits);
                if (offset >= 0 && offset < end) {
                    Arrays.fill(data, offset, end, 0.0f);
                }
                return;
            }
            if (buffer instanceof ByteBuffer) {
                ByteBuffer byteBuffer = (ByteBuffer) buffer;
                int start = context.byteBufferStart(byteBuffer, resultUnits);
                int end = start + resultUnits;
                if (start < 0 || end > byteBuffer.limit()) {
                    return;
                }
                ByteBuffer dup = byteBuffer.duplicate();
                dup.position(start);
                dup.limit(end);
                while (dup.remaining() > 0) {
                    int chunk = Math.min(dup.remaining(), ZERO_BYTES.length);
                    dup.put(ZERO_BYTES, 0, chunk);
                }
            }
        }
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
                int start = byteBufferStart(byteBuffer, resultUnits);
                return start >= 0 && (long) start + (long) resultUnits <= (long) byteBuffer.limit();
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

        int byteBufferStart(ByteBuffer byteBuffer, int resultUnits) {
            if (byteBuffer == null || resultUnits <= 0 || resultUnits > byteBuffer.limit()) {
                return -1;
            }
            int position = byteBuffer.position();
            int start = position - resultUnits;
            return Math.max(start, 0);
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
            int position = context.byteBufferStart(buffer, resultUnits);
            if (position < 0) return false;
            boolean success = NativeBridge.processByteBuffer(
                    buffer,
                    position,
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
