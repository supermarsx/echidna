package com.echidna.magisk;

import android.content.pm.PackageInfo;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;

/** Isolated API-28 references so Android 26/27 never resolve SigningInfo. */
final class ModernSignerReader {
    private ModernSignerReader() {}

    static Evidence read(PackageInfo packageInfo) {
        SigningInfo signingInfo = packageInfo.signingInfo;
        if (signingInfo == null) {
            throw new IllegalArgumentException("PackageManager returned no SigningInfo");
        }
        return new Evidence(
                encode(signingInfo.getApkContentsSigners()),
                encode(signingInfo.getSigningCertificateHistory()),
                signingInfo.hasMultipleSigners());
    }

    private static byte[][] encode(Signature[] signatures) {
        if (signatures == null) {
            return null;
        }
        byte[][] encoded = new byte[signatures.length][];
        for (int index = 0; index < signatures.length; index++) {
            encoded[index] = signatures[index] == null ? null : signatures[index].toByteArray();
        }
        return encoded;
    }

    static final class Evidence {
        final byte[][] current;
        final byte[][] history;
        final boolean multiple;

        Evidence(byte[][] current, byte[][] history, boolean multiple) {
            this.current = current;
            this.history = history;
            this.multiple = multiple;
        }
    }
}
