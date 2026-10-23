package com.demo.upimesh.client;

import com.demo.upimesh.crypto.BankKeyStore;
import com.demo.upimesh.crypto.HybridCrypto;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.PaymentInstruction;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.security.GeneralSecurityException;
import java.time.Instant;
import java.util.UUID;

/**
 * The sender's phone app. It only holds the bank's public key, so it can seal a payment
 * but nobody on the mesh (including the sender's own relays) can open it again.
 */
@Component
public class PacketFactory {

    private final ObjectMapper mapper;
    private final BankKeyStore bankKeys;
    private final int initialTtl;

    public PacketFactory(ObjectMapper mapper, BankKeyStore bankKeys,
                         @Value("${upimesh.packet.initial-ttl:4}") int initialTtl) {
        this.mapper = mapper;
        this.bankKeys = bankKeys;
        this.initialTtl = initialTtl;
    }

    public MeshPacket create(String senderVpa, String receiverVpa, BigDecimal amount, String pin, Instant signedAt) {
        PaymentInstruction instruction = new PaymentInstruction(
                senderVpa, receiverVpa, amount, pin, UUID.randomUUID().toString(), signedAt.toEpochMilli());
        try {
            byte[] plaintext = mapper.writeValueAsBytes(instruction);
            return MeshPacket.of(HybridCrypto.encrypt(plaintext, bankKeys.publicKey()), initialTtl);
        } catch (JsonProcessingException | GeneralSecurityException e) {
            throw new IllegalStateException("Could not seal payment", e);
        }
    }
}
