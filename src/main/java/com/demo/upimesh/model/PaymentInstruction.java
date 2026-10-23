package com.demo.upimesh.model;

import java.math.BigDecimal;

/**
 * What the sender actually wants. This only ever exists in plaintext on the sender's
 * phone and inside the bank; on the mesh it is always encrypted.
 *
 * @param nonce          random per payment, so two identical ₹100 payments get different packets
 * @param signedAtEpochMs when the sender created it; sealed inside the ciphertext so relays can't refresh it
 */
public record PaymentInstruction(
        String senderVpa,
        String receiverVpa,
        BigDecimal amount,
        String pin,
        String nonce,
        long signedAtEpochMs) {
}
