package com.demo.upimesh.crypto;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

public final class Hashing {

    private Hashing() {
    }

    public static byte[] sha256(String... parts) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            for (String part : parts) {
                digest.update(part.getBytes(StandardCharsets.UTF_8));
                digest.update((byte) 0); // separator, so ("ab","c") and ("a","bc") differ
            }
            return digest.digest();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory on every JVM", e);
        }
    }

    public static String sha256Hex(String... parts) {
        return HexFormat.of().formatHex(sha256(parts));
    }

    /** POC only: a real bank verifies the PIN inside its HSM, never as a plain hash. */
    public static byte[] pinHash(String vpa, String pin) {
        return sha256("upi-pin", vpa, pin);
    }
}
