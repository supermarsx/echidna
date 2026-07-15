package com.echidna.magisk;

import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.interfaces.ECPublicKey;
import java.security.spec.ECFieldFp;
import java.security.spec.ECParameterSpec;
import java.security.spec.ECPoint;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

/** Canonical DER SubjectPublicKeyInfo validation for the capability P-256 key. */
public final class SpkiPolicy {
    public static final int MAX_SPKI_BYTES = 1024;
    private static final BigInteger P = hex(
            "ffffffff00000001000000000000000000000000ffffffffffffffffffffffff");
    private static final BigInteger A = hex(
            "ffffffff00000001000000000000000000000000fffffffffffffffffffffffc");
    private static final BigInteger B = hex(
            "5ac635d8aa3a93e7b3ebbd55769886bc651d06b0cc53b0f63bce3c3e27d2604b");
    private static final BigInteger GX = hex(
            "6b17d1f2e12c4247f8bce6e563a440f277037d812deb33a0f4a13945d898c296");
    private static final BigInteger GY = hex(
            "4fe342e2fe1a7f9b8ee7eb4a7c0f9e162bce33576b315ececbb6406837bf51f5");
    private static final BigInteger N = hex(
            "ffffffff00000000ffffffffffffffffbce6faada7179e84f3b9cac2fc632551");

    private SpkiPolicy() {}

    public static String verifyP256(byte[] encoded) {
        if (encoded == null || encoded.length == 0 || encoded.length > MAX_SPKI_BYTES) {
            throw new IllegalArgumentException("SPKI must contain 1..1024 bytes");
        }
        try {
            PublicKey parsed = KeyFactory.getInstance("EC")
                    .generatePublic(new X509EncodedKeySpec(encoded));
            if (!(parsed instanceof ECPublicKey)) {
                throw new IllegalArgumentException("SPKI is not an EC public key");
            }
            if (!Arrays.equals(encoded, parsed.getEncoded())) {
                throw new IllegalArgumentException("SPKI is not canonical DER SubjectPublicKeyInfo");
            }
            ECPublicKey publicKey = (ECPublicKey) parsed;
            requireP256(publicKey.getParams());
            requirePointOnCurve(publicKey.getW());
            return SignerPolicy.sha256(encoded);
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("SPKI is not a valid P-256 public key", exception);
        }
    }

    private static void requireP256(ECParameterSpec parameters) {
        if (parameters == null
                || !(parameters.getCurve().getField() instanceof ECFieldFp)
                || !P.equals(((ECFieldFp) parameters.getCurve().getField()).getP())
                || !A.equals(parameters.getCurve().getA())
                || !B.equals(parameters.getCurve().getB())
                || !new ECPoint(GX, GY).equals(parameters.getGenerator())
                || !N.equals(parameters.getOrder())
                || parameters.getCofactor() != 1) {
            throw new IllegalArgumentException("SPKI curve is not secp256r1/P-256");
        }
    }

    private static void requirePointOnCurve(ECPoint point) {
        if (point == null || ECPoint.POINT_INFINITY.equals(point)) {
            throw new IllegalArgumentException("SPKI contains the point at infinity");
        }
        BigInteger x = point.getAffineX();
        BigInteger y = point.getAffineY();
        if (x.signum() < 0
                || x.compareTo(P) >= 0
                || y.signum() < 0
                || y.compareTo(P) >= 0
                || !y.multiply(y).mod(P)
                        .equals(x.multiply(x).multiply(x).add(A.multiply(x)).add(B).mod(P))) {
            throw new IllegalArgumentException("SPKI public point is not on P-256");
        }
    }

    private static BigInteger hex(String value) {
        return new BigInteger(value, 16);
    }
}
