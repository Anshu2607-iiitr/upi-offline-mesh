package com.demo.upimesh.web;

import com.demo.upimesh.bank.BankService;
import com.demo.upimesh.client.PacketFactory;
import com.demo.upimesh.crypto.BankKeyStore;
import com.demo.upimesh.mesh.EventLog;
import com.demo.upimesh.mesh.MeshSimulator;
import com.demo.upimesh.model.IngestResult;
import com.demo.upimesh.model.MeshPacket;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

@RestController
@RequestMapping("/api")
public class MeshController {

    private final MeshSimulator mesh;
    private final BankService bank;
    private final PacketFactory packets;
    private final BankKeyStore bankKeys;
    private final EventLog events;
    private final Clock clock;

    public MeshController(MeshSimulator mesh, BankService bank, PacketFactory packets,
                          BankKeyStore bankKeys, EventLog events, Clock clock) {
        this.mesh = mesh;
        this.bank = bank;
        this.packets = packets;
        this.bankKeys = bankKeys;
        this.events = events;
        this.clock = clock;
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        Map<String, Object> state = new LinkedHashMap<>();
        state.put("devices", mesh.devices());
        state.put("edges", mesh.edges());
        state.put("accounts", bank.accounts());
        state.put("ledger", bank.ledger(50));
        state.put("events", events.recent(60));
        state.put("idempotencyKeys", bank.idempotencyKeyCount());
        return state;
    }

    /** The sender's phone seals the payment and starts broadcasting. No internet involved. */
    @PostMapping("/send")
    public Map<String, Object> send(@Valid @RequestBody SendRequest req) {
        if (!mesh.hasDevice(req.deviceId())) {
            throw new NoSuchElementException("No device '" + req.deviceId() + "'");
        }
        MeshPacket packet = packets.create(req.senderVpa(), req.receiverVpa(), req.amount(), req.pin(), clock.instant());
        mesh.inject(req.deviceId(), packet);
        return Map.of(
                "packetId", packet.packetId(),
                "ttl", packet.ttl(),
                "whatStrangersSee", packet.ciphertext().substring(0, Math.min(72, packet.ciphertext().length())) + "…",
                "ciphertextBytes", packet.envelope().ciphertext().length);
    }

    @PostMapping("/gossip")
    public MeshSimulator.GossipSummary gossip() {
        return mesh.gossipRound();
    }

    @PostMapping("/flush")
    public List<IngestResult> flush() throws InterruptedException {
        return mesh.flushBridges();
    }

    @PostMapping("/devices/{id}/internet")
    public Map<String, Object> toggleInternet(@PathVariable String id) {
        return Map.of("id", id, "hasInternet", mesh.toggleInternet(id));
    }

    @PostMapping("/reset")
    public Map<String, String> reset() {
        bank.reset();
        mesh.reset();
        events.clear();
        events.add("RESET", "Fresh market: balances restored, mesh cleared");
        return Map.of("status", "ok");
    }

    /** The endpoint a real bridge phone would call once it has connectivity. */
    @PostMapping("/bridge/ingest")
    public IngestResult ingest(@RequestBody MeshPacket packet,
                               @RequestHeader(value = "X-Bridge-Id", defaultValue = "external") String bridgeId) {
        return bank.ingest(packet, bridgeId);
    }

    @GetMapping("/bank/public-key")
    public Map<String, String> publicKey() {
        return Map.of("algorithm", "RSA-2048 / OAEP-SHA256",
                "key", Base64.getEncoder().encodeToString(bankKeys.publicKey().getEncoded()));
    }

    // ---- Attack demos -----------------------------------------------------------------

    /** A relay edits a sealed ₹100 packet hoping to change the amount. */
    @PostMapping("/demo/tamper")
    public IngestResult tamper() {
        MeshPacket genuine = packets.create("pratyush@sbi", "mohan@hdfc", new BigDecimal("100"), "1234", clock.instant());
        byte[] ct = genuine.envelope().ciphertext();
        ct[ct.length / 2] ^= 0x01; // flip one bit
        MeshPacket forged = new MeshPacket(genuine.packetId(), genuine.ttl(), genuine.encryptedKey(), genuine.iv(),
                Base64.getEncoder().encodeToString(ct));
        IngestResult result = bank.ingest(forged, "manoj (attacker)");
        events.add("ATTACK", "Manoj flipped 1 bit of a sealed ₹100 packet and uploaded it → " + result.outcome());
        return result;
    }

    /** A relay saved yesterday's packet and uploads it again. */
    @PostMapping("/demo/replay")
    public ResponseEntity<?> replay() {
        return mesh.lastUploaded()
                .<ResponseEntity<?>>map(p -> {
                    IngestResult result = bank.ingest(p, "manoj (replay)");
                    events.add("ATTACK", "Manoj re-uploaded saved packet " + MeshSimulator.shortId(p.packetId())
                            + " → " + result.outcome());
                    return ResponseEntity.ok(result);
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("error", "Send a payment and flush it to the bank first")));
    }

    /** A packet signed 25 hours ago turns up — e.g. replayed after the bank forgot its key. */
    @PostMapping("/demo/expired")
    public IngestResult expired() {
        MeshPacket stale = packets.create("pratyush@sbi", "mohan@hdfc", new BigDecimal("100"), "1234",
                clock.instant().minus(Duration.ofHours(25)));
        IngestResult result = bank.ingest(stale, "manoj (stale)");
        events.add("ATTACK", "A 25-hour-old packet was uploaded → " + result.outcome());
        return result;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .findFirst().orElse("Invalid request");
        return ResponseEntity.badRequest().body(Map.of("error", msg));
    }

    @ExceptionHandler(NoSuchElementException.class)
    public ResponseEntity<Map<String, String>> notFound(NoSuchElementException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("error", e.getMessage()));
    }
}
