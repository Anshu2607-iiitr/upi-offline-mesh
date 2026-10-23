package com.demo.upimesh.crypto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.spec.MGF1ParameterSpec;

/**
 * Hybrid encryption: AES-256-GCM for the payload (fast, and the GCM tag detects any
 * tampering), RSA-OAEP to lock the one-time AES key so only the bank can open it.
 *
 * Relaying phones see only opaque bytes. Flipping a single bit anywhere in the envelope
 * makes {@link #decrypt} throw, which is how the bank spots a "₹100 changed to ₹200" edit.
 */
public final class HybridCrypto {

    private static final String RSA_TRANSFORM = "RSA/ECB/OAEPPadding";
    private static final OAEPParameterSpec OAEP_SHA256 = new OAEPParameterSpec(
            "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
    private static final String AES_TRANSFORM = "AES/GCM/NoPadding";
    private static final int AES_KEY_BITS = 256;
    private static final int GCM_IV_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;

    private static final SecureRandom RANDOM = new SecureRandom();

    private HybridCrypto() {
    }

    public static Envelope encrypt(byte[] plaintext, PublicKey bankPublicKey) throws GeneralSecurityException {
        KeyGenerator keyGen = KeyGenerator.getInstance("AES");
        keyGen.init(AES_KEY_BITS, RANDOM);
        SecretKey aesKey = keyGen.generateKey();

        byte[] iv = new byte[GCM_IV_BYTES];
        RANDOM.nextBytes(iv);

        Cipher aes = Cipher.getInstance(AES_TRANSFORM);
        aes.init(Cipher.ENCRYPT_MODE, aesKey, new GCMParameterSpec(GCM_TAG_BITS, iv));
        byte[] ciphertext = aes.doFinal(plaintext);

        Cipher rsa = Cipher.getInstance(RSA_TRANSFORM);
        rsa.init(Cipher.ENCRYPT_MODE, bankPublicKey, OAEP_SHA256);
        byte[] wrappedKey = rsa.doFinal(aesKey.getEncoded());

        return new Envelope(wrappedKey, iv, ciphertext);
    }

    public static byte[] decrypt(Envelope envelope, PrivateKey bankPrivateKey) throws GeneralSecurityException {
        Cipher rsa = Cipher.getInstance(RSA_TRANSFORM);
        rsa.init(Cipher.DECRYPT_MODE, bankPrivateKey, OAEP_SHA256);
        byte[] rawKey = rsa.doFinal(envelope.encryptedKey());

        Cipher aes = Cipher.getInstance(AES_TRANSFORM);
        aes.init(Cipher.DECRYPT_MODE, new SecretKeySpec(rawKey, "AES"),
                new GCMParameterSpec(GCM_TAG_BITS, envelope.iv()));
        return aes.doFinal(envelope.ciphertext());
    }
}
