package com.demo.upimesh.crypto;

/**
 * Output of hybrid encryption.
 *
 * @param encryptedKey the one-time AES key, wrapped with the bank's RSA public key
 * @param iv           the 12-byte AES-GCM nonce
 * @param ciphertext   the AES-GCM ciphertext, with the 16-byte auth tag appended
 */
public record Envelope(byte[] encryptedKey, byte[] iv, byte[] ciphertext) {
}
