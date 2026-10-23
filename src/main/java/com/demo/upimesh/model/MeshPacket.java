package com.demo.upimesh.model;

import com.demo.upimesh.crypto.Envelope;
import com.demo.upimesh.crypto.Hashing;

import java.util.Base64;

/**
 * What travels phone-to-phone over Bluetooth. Everything except {@code ttl} is opaque
 * ciphertext; {@code ttl} is the hop budget that relays decrement while gossiping.
 *
 * {@code packetId} is a hash of the encrypted bytes. Relays use it to drop copies they've
 * already seen; the bank recomputes it rather than trusting this field.
 */
public record MeshPacket(String packetId, int ttl, String encryptedKey, String iv, String ciphertext) {

    private static final Base64.Encoder B64 = Base64.getEncoder();
    private static final Base64.Decoder B64D = Base64.getDecoder();

    public static MeshPacket of(Envelope envelope, int ttl) {
        String key = B64.encodeToString(envelope.encryptedKey());
        String iv = B64.encodeToString(envelope.iv());
        String ct = B64.encodeToString(envelope.ciphertext());
        return new MeshPacket(idOf(key, iv, ct), ttl, key, iv, ct);
    }

    public static String idOf(String encryptedKey, String iv, String ciphertext) {
        return Hashing.sha256Hex(encryptedKey, iv, ciphertext);
    }

    /** @throws IllegalArgumentException if any field isn't valid Base64 */
    public Envelope envelope() {
        return new Envelope(B64D.decode(encryptedKey), B64D.decode(iv), B64D.decode(ciphertext));
    }

    public MeshPacket withTtl(int newTtl) {
        return new MeshPacket(packetId, newTtl, encryptedKey, iv, ciphertext);
    }
}
