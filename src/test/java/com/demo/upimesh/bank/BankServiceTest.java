package com.demo.upimesh.bank;

import com.demo.upimesh.client.PacketFactory;
import com.demo.upimesh.model.IngestResult;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.Outcome;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.SpyBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.dao.DataAccessResourceFailureException;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:bank-test;DB_CLOSE_DELAY=-1")
class BankServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-23T10:00:00Z");

    @TestConfiguration
    static class FixedClock {
        @Bean
        @Primary
        Clock fixedClock() {
            return Clock.fixed(NOW, ZoneOffset.UTC);
        }
    }

    @Autowired
    BankService bank;
    @Autowired
    PacketFactory phone;
    @Autowired
    IdempotencyStore idempotency;
    @Autowired
    LedgerRepository ledger;
    @SpyBean
    AccountRepository accounts;

    @BeforeEach
    void setUp() {
        Mockito.reset(accounts);
        bank.reset();
    }

    private MeshPacket pay(String amount, String pin, Instant signedAt) {
        return phone.create("pratyush@sbi", "mohan@hdfc", new BigDecimal(amount), pin, signedAt);
    }

    @Test
    void validPacketSettlesAndMovesMoney() {
        IngestResult r = bank.ingest(pay("100", "1234", NOW), "sunita");

        assertThat(r.outcome()).isEqualTo(Outcome.SETTLED);
        assertThat(bank.balanceOf("pratyush@sbi")).isEqualByComparingTo("4900");
        assertThat(bank.balanceOf("mohan@hdfc")).isEqualByComparingTo("1100");
    }

    // Challenge 1: relays see only ciphertext, and any edit is caught.
    @Test
    void relayCannotReadThePacket() {
        MeshPacket p = pay("100", "1234", NOW);
        String visible = p.encryptedKey() + p.iv() + p.ciphertext();
        assertThat(visible).doesNotContain("pratyush", "mohan", "amount", "pin");
    }

    @Test
    void flippingOneBitIsRejectedAsTampered() {
        MeshPacket genuine = pay("100", "1234", NOW);
        byte[] ct = Base64.getDecoder().decode(genuine.ciphertext());
        ct[ct.length / 2] ^= 0x01;
        MeshPacket forged = new MeshPacket(genuine.packetId(), 4, genuine.encryptedKey(), genuine.iv(),
                Base64.getEncoder().encodeToString(ct));

        assertThat(bank.ingest(forged, "manoj").outcome()).isEqualTo(Outcome.REJECTED_TAMPERED);
        assertThat(bank.balanceOf("pratyush@sbi")).isEqualByComparingTo("5000");
    }

    @Test
    void swappingTheWrappedKeyIsRejectedAsTampered() {
        MeshPacket a = pay("100", "1234", NOW);
        MeshPacket b = pay("100", "1234", NOW);
        MeshPacket spliced = new MeshPacket(a.packetId(), 4, b.encryptedKey(), a.iv(), a.ciphertext());

        assertThat(bank.ingest(spliced, "manoj").outcome()).isEqualTo(Outcome.REJECTED_TAMPERED);
    }

    // Challenge 2: three bridges racing with the same packet → one debit.
    @Test
    void threeBridgesUploadingConcurrentlySettleExactlyOnce() throws Exception {
        MeshPacket p = pay("100", "1234", NOW);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IngestResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(3)) {
            for (String bridge : List.of("manoj", "sunita", "ajay")) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return bank.ingest(p, bridge);
                }));
            }
            start.countDown();
        }

        List<Outcome> outcomes = new ArrayList<>();
        for (Future<IngestResult> f : futures) {
            outcomes.add(f.get().outcome());
        }
        assertThat(outcomes).containsOnlyOnce(Outcome.SETTLED);
        assertThat(outcomes).filteredOn(o -> o == Outcome.DUPLICATE_DROPPED).hasSize(2);
        assertThat(bank.balanceOf("pratyush@sbi")).isEqualByComparingTo("4900");
        assertThat(ledger.countByPacketIdAndOutcome(p.packetId(), Outcome.SETTLED)).isEqualTo(1);
        assertThat(ledger.countByPacketIdAndOutcome(p.packetId(), Outcome.DUPLICATE_DROPPED)).isEqualTo(2);
    }

    @Test
    void differentPaymentsFromSameSenderSettlingConcurrentlyAreAllApplied() throws Exception {
        List<MeshPacket> payments = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            payments.add(pay("100", "1234", NOW));
        }
        CountDownLatch start = new CountDownLatch(1);
        List<Future<IngestResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(10)) {
            for (MeshPacket p : payments) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return bank.ingest(p, "sunita");
                }));
            }
            start.countDown();
        }

        for (Future<IngestResult> f : futures) {
            assertThat(f.get().outcome()).isEqualTo(Outcome.SETTLED);
        }
        assertThat(bank.balanceOf("pratyush@sbi")).isEqualByComparingTo("4000");
        assertThat(bank.balanceOf("mohan@hdfc")).isEqualByComparingTo("2000");
    }

    @Test
    void failedSettlementReleasesClaimSoTheNextCopyCanStillSettle() {
        MeshPacket p = pay("100", "1234", NOW);
        Mockito.doThrow(new DataAccessResourceFailureException("db down"))
                .when(accounts).findById("pratyush@sbi");

        assertThatThrownBy(() -> bank.ingest(p, "sunita")).isInstanceOf(DataAccessResourceFailureException.class);
        Mockito.reset(accounts); // database is back
        assertThat(bank.balanceOf("pratyush@sbi")).isEqualByComparingTo("5000");

        assertThat(bank.ingest(p, "ajay").outcome()).isEqualTo(Outcome.SETTLED);
        assertThat(bank.balanceOf("pratyush@sbi")).isEqualByComparingTo("4900");
    }

    @Test
    void relayCannotDodgeDedupeByRewritingPacketIdOrTtl() {
        MeshPacket p = pay("100", "1234", NOW);
        bank.ingest(p, "sunita");
        MeshPacket relabelled = new MeshPacket("deadbeef".repeat(8), 0, p.encryptedKey(), p.iv(), p.ciphertext());

        assertThat(bank.ingest(relabelled, "manoj").outcome()).isEqualTo(Outcome.DUPLICATE_DROPPED);
        assertThat(bank.balanceOf("pratyush@sbi")).isEqualByComparingTo("4900");
    }

    @Test
    void twoGenuinePaymentsOfSameAmountBothSettle() {
        assertThat(bank.ingest(pay("100", "1234", NOW), "sunita").outcome()).isEqualTo(Outcome.SETTLED);
        assertThat(bank.ingest(pay("100", "1234", NOW), "sunita").outcome()).isEqualTo(Outcome.SETTLED);
        assertThat(bank.balanceOf("pratyush@sbi")).isEqualByComparingTo("4800");
    }

    // Challenge 3: replay.
    @Test
    void replayingNextDayIsDropped() {
        MeshPacket p = pay("100", "1234", NOW);
        bank.ingest(p, "sunita");

        assertThat(bank.ingest(p, "manoj").outcome()).isEqualTo(Outcome.DUPLICATE_DROPPED);
        assertThat(bank.balanceOf("pratyush@sbi")).isEqualByComparingTo("4900");
    }

    @Test
    void packetOlderThanMaxAgeIsRejectedEvenIfBankForgotItsKey() {
        MeshPacket stale = pay("100", "1234", NOW.minus(Duration.ofHours(25)));

        assertThat(bank.ingest(stale, "manoj").outcome()).isEqualTo(Outcome.REJECTED_EXPIRED);
        assertThat(idempotency.size()).isZero();
    }

    @Test
    void packetFromTheFutureIsRejected() {
        MeshPacket future = pay("100", "1234", NOW.plus(Duration.ofHours(1)));
        assertThat(bank.ingest(future, "manoj").outcome()).isEqualTo(Outcome.REJECTED_EXPIRED);
    }

    @Test
    void evictionOnlyForgetsKeysWhosePacketsWouldNowBeExpired() {
        bank.ingest(pay("100", "1234", NOW), "sunita");
        bank.evictExpiredKeys();
        assertThat(idempotency.size()).isEqualTo(1);

        idempotency.evictClaimedBefore(NOW.plusSeconds(1));
        assertThat(idempotency.size()).isZero();
    }

    // Ordinary UPI rules still apply.
    @Test
    void insufficientFundsIsRejected() {
        assertThat(bank.ingest(pay("100000", "1234", NOW), "sunita").outcome())
                .isEqualTo(Outcome.REJECTED_INSUFFICIENT_FUNDS);
        assertThat(bank.balanceOf("pratyush@sbi")).isEqualByComparingTo("5000");
    }

    @Test
    void wrongPinIsRejected() {
        assertThat(bank.ingest(pay("100", "9999", NOW), "sunita").outcome()).isEqualTo(Outcome.REJECTED_BAD_PIN);
    }
}
