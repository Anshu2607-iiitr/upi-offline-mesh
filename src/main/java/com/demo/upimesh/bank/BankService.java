package com.demo.upimesh.bank;

import com.demo.upimesh.crypto.BankKeyStore;
import com.demo.upimesh.model.IngestResult;
import com.demo.upimesh.model.LedgerEntry;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.Outcome;
import com.demo.upimesh.model.PaymentInstruction;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.math.BigDecimal;
import java.security.GeneralSecurityException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * The bank's server. Bridges (phones that do have internet) hand it packets they picked up
 * from the mesh; it decides, exactly once per packet, whether money moves.
 *
 * Order of checks matters:
 * 1. decrypt   - anything tampered fails the AES-GCM tag and is dropped before it costs us state
 * 2. freshness - signed more than max-age ago means a stale replay
 * 3. claim     - atomic putIfAbsent on the packet hash; losers are duplicates
 * 4. settle    - PIN, accounts, balance, then move the money
 *
 * Accounts and the ledger live in the database; debit, credit and the SETTLED ledger row
 * commit in one transaction, so a crash can never leave money half-moved.
 */
@Service
public class BankService {

    /** Phones' clocks drift; accept packets stamped slightly in the future. */
    static final Duration MAX_CLOCK_SKEW = Duration.ofMinutes(5);

    private final BankKeyStore keyStore;
    private final IdempotencyStore idempotency;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Duration maxAge;
    private final AccountRepository accounts;
    private final LedgerRepository ledger;
    private final TransactionTemplate tx;

    BankService(BankKeyStore keyStore, IdempotencyStore idempotency, ObjectMapper mapper, Clock clock,
                @Value("${upimesh.packet.max-age:PT24H}") Duration maxAge,
                AccountRepository accounts, LedgerRepository ledger, PlatformTransactionManager txManager) {
        this.keyStore = keyStore;
        this.idempotency = idempotency;
        this.mapper = mapper;
        this.clock = clock;
        this.maxAge = maxAge;
        this.accounts = accounts;
        this.ledger = ledger;
        this.tx = new TransactionTemplate(txManager);
    }

    @PostConstruct
    public synchronized void reset() {
        tx.executeWithoutResult(status -> {
            // Bulk deletes run immediately; plain deleteAll() would be flushed after the inserts below.
            ledger.deleteAllInBatch();
            accounts.deleteAllInBatch();
            // Demo data only.
            accounts.saveAll(List.of(
                    new Account("pratyush@sbi", "Pratyush Narayan", "1234", new BigDecimal("5000.00")),
                    new Account("mohan@hdfc", "Mohan Kumar", "4321", new BigDecimal("1000.00")),
                    new Account("sunita@icici", "Sunita Devi", "1111", new BigDecimal("2500.00"))));
        });
        idempotency.clear();
    }

    public IngestResult ingest(MeshPacket packet, String bridgeId) {
        // Never trust the id a relay claims; derive it from the bytes we're about to act on.
        String packetId = MeshPacket.idOf(packet.encryptedKey(), packet.iv(), packet.ciphertext());

        PaymentInstruction instruction;
        try {
            byte[] plaintext = keyStore.decrypt(packet.envelope());
            instruction = mapper.readValue(plaintext, PaymentInstruction.class);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return record(packetId, bridgeId, null, Outcome.REJECTED_TAMPERED,
                    "Integrity check failed - packet was modified or not sealed for this bank");
        } catch (IOException e) {
            return record(packetId, bridgeId, null, Outcome.REJECTED_INVALID, "Decrypted payload is not a payment");
        }

        Instant now = clock.instant();
        Instant signedAt = Instant.ofEpochMilli(instruction.signedAtEpochMs());
        if (signedAt.isBefore(now.minus(maxAge)) || signedAt.isAfter(now.plus(MAX_CLOCK_SKEW))) {
            return record(packetId, bridgeId, instruction, Outcome.REJECTED_EXPIRED,
                    "Signed at " + signedAt + ", outside the " + maxAge.toHours() + "h validity window");
        }

        if (!idempotency.claim(packetId, now)) {
            return record(packetId, bridgeId, instruction, Outcome.DUPLICATE_DROPPED,
                    "Already processed - this copy is ignored");
        }

        return settle(packetId, bridgeId, instruction);
    }

    // One global lock keeps the POC obviously correct and means @Version never has to fire.
    // A real ledger would SELECT ... FOR UPDATE the two account rows in a fixed order instead.
    private synchronized IngestResult settle(String packetId, String bridgeId, PaymentInstruction ins) {
        try {
            return tx.execute(status -> settleInTransaction(packetId, bridgeId, ins));
        } catch (RuntimeException e) {
            // The transaction rolled back, so no money moved. Give the claim back, otherwise every
            // later copy of this packet would be dropped as a duplicate and the payment lost for good.
            idempotency.release(packetId);
            throw e;
        }
    }

    private IngestResult settleInTransaction(String packetId, String bridgeId, PaymentInstruction ins) {
        Account from = accounts.findById(ins.senderVpa()).orElse(null);
        Account to = accounts.findById(ins.receiverVpa()).orElse(null);
        if (from == null || to == null || from == to) {
            return record(packetId, bridgeId, ins, Outcome.REJECTED_UNKNOWN_ACCOUNT, "Unknown sender or receiver VPA");
        }
        if (!from.pinMatches(ins.pin())) {
            return record(packetId, bridgeId, ins, Outcome.REJECTED_BAD_PIN, "Incorrect UPI PIN");
        }
        if (ins.amount() == null || ins.amount().signum() <= 0) {
            return record(packetId, bridgeId, ins, Outcome.REJECTED_INVALID, "Amount must be positive");
        }
        if (from.balance().compareTo(ins.amount()) < 0) {
            return record(packetId, bridgeId, ins, Outcome.REJECTED_INSUFFICIENT_FUNDS,
                    "Balance ₹" + from.balance() + " is less than ₹" + ins.amount());
        }
        from.debit(ins.amount());
        to.credit(ins.amount());
        return record(packetId, bridgeId, ins, Outcome.SETTLED,
                "₹" + ins.amount() + " moved " + from.vpa() + " → " + to.vpa());
    }

    private IngestResult record(String packetId, String bridgeId, PaymentInstruction ins, Outcome outcome, String message) {
        ledger.save(new LedgerEntry(clock.instant(), packetId, bridgeId,
                ins == null ? null : ins.senderVpa(),
                ins == null ? null : ins.receiverVpa(),
                ins == null ? null : ins.amount(),
                outcome, message));
        return new IngestResult(packetId, bridgeId, outcome, message);
    }

    /**
     * Keys only need to outlive the validity window. A key claimed at time C belongs to a packet
     * signed no later than C + skew, so once now > C + maxAge + skew that packet fails the
     * freshness check before it ever reaches {@link IdempotencyStore#claim}.
     */
    @Scheduled(fixedDelay = 60_000)
    public void evictExpiredKeys() {
        idempotency.evictClaimedBefore(clock.instant().minus(maxAge).minus(MAX_CLOCK_SKEW));
    }

    public List<AccountView> accounts() {
        return accounts.findAll().stream()
                .map(a -> new AccountView(a.vpa(), a.holderName(), a.balance()))
                .toList();
    }

    public BigDecimal balanceOf(String vpa) {
        return accounts.findById(vpa).orElseThrow().balance();
    }

    public List<LedgerEntry> ledger(int limit) {
        return ledger.findAllByOrderByIdDesc(PageRequest.of(0, limit));
    }

    public int idempotencyKeyCount() {
        return idempotency.size();
    }
}
