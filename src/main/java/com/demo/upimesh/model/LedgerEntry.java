package com.demo.upimesh.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * One line in the bank's audit trail - every upload is recorded, including duplicates and
 * rejections. Sender/receiver/amount are null when the packet couldn't be opened.
 */
@Entity
@Table(name = "ledger", indexes = @Index(name = "idx_ledger_packet", columnList = "packetId"))
public class LedgerEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Instant at;

    @Column(nullable = false, length = 64)
    private String packetId;

    private String bridgeId;
    private String senderVpa;
    private String receiverVpa;

    @Column(precision = 19, scale = 2)
    private BigDecimal amount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private Outcome outcome;

    @Column(length = 500)
    private String message;

    protected LedgerEntry() {
    }

    public LedgerEntry(Instant at, String packetId, String bridgeId, String senderVpa, String receiverVpa,
                       BigDecimal amount, Outcome outcome, String message) {
        this.at = at;
        this.packetId = packetId;
        this.bridgeId = bridgeId;
        this.senderVpa = senderVpa;
        this.receiverVpa = receiverVpa;
        this.amount = amount;
        this.outcome = outcome;
        this.message = message;
    }

    public Long getId() {
        return id;
    }

    public Instant getAt() {
        return at;
    }

    public String getPacketId() {
        return packetId;
    }

    public String getBridgeId() {
        return bridgeId;
    }

    public String getSenderVpa() {
        return senderVpa;
    }

    public String getReceiverVpa() {
        return receiverVpa;
    }

    public BigDecimal getAmount() {
        return amount;
    }

    public Outcome getOutcome() {
        return outcome;
    }

    public String getMessage() {
        return message;
    }
}
