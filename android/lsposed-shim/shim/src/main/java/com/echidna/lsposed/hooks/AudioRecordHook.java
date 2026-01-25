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
                if (context.isValid()) {
                    zeroBuffer(context, samplesOrBytes);
                }
                param.setResult(0);
                return;
            }
            if (!state.shouldProcessAudio()) {
                return;
            }
            BufferContext context = BufferContext.from(param);
            if (!context.isValid()) {
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
                int end = Math.min(data.length, offset + resultUnits);
                if (offset >= 0 && offset < end) {
                    Arrays.fill(data, offset, end, (byte) 0);
                }
                return;
            }
            if (buffer instanceof short[]) {
                int offset = (Integer) context.args[1];
                short[] data = (short[]) buffer;
                int end = Math.min(data.length, offset + resultUnits);
                if (offset >= 0 && offset < end) {
                    Arrays.fill(data, offset, end, (short) 0);
                }
                return;
            }
            if (buffer instanceof float[]) {
                int offset = (Integer) context.args[1];
                float[] data = (float[]) buffer;
                int end = Math.min(data.length, offset + resultUnits);
                if (offset >= 0 && offset < end) {
                    Arrays.fill(data, offset, end, 0.0f);
                }
                return;
            }
            if (buffer instanceof ByteBuffer) {
                ByteBuffer byteBuffer = (ByteBuffer) buffer;
                int end = Math.min(byteBuffer.position(), byteBuffer.capacity());
                int start = end - resultUnits;
                if (start < 0) {
                    start = 0;
                }
                if (start >= end) {
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

        static BufferContext from(MethodHookParam param) {
            AudioRecord record = (AudioRecord) param.thisObject;
            int sampleRate = record.getSampleRate();
            int channelCount = record.getChannelCount();
            int encoding = record.getAudioFormat();
            return new BufferContext(record, param.args, sampleRate, channelCount, encoding);
        }

        boolean isValid() {
            return sampleRate > 0 && channelCount > 0;
        }

        int framesFromBytes(int bytes) {
            int bps = AudioFormatUtils.bytesPerSample(encoding);
            if (bps <= 0) {
                return 0;
            }
            return bytes / (bps * Math.max(channelCount, 1));
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
            int position = buffer.position() - resultUnits;
            if (position < 0) {
                position = 0;
            }
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
}
