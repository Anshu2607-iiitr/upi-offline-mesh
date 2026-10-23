package com.demo.upimesh.mesh;

import com.demo.upimesh.bank.BankService;
import com.demo.upimesh.model.IngestResult;
import com.demo.upimesh.model.MeshPacket;
import com.demo.upimesh.model.Outcome;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * Simulates phones in a market that can only reach each other over Bluetooth. Edges are
 * "within Bluetooth range". Some phones have mobile data and act as bridges to the bank.
 */
@Service
public class MeshSimulator {

    public record Edge(String a, String b) {
    }

    public record DeviceView(String id, String owner, int x, int y, boolean hasInternet,
                             List<String> heldPacketIds) {
    }

    public record Hop(String from, String to, String packetId) {
    }

    public record GossipSummary(int transfers, int packetsInFlight, List<Hop> hops) {
    }

    private record Upload(String bridgeId, MeshPacket packet) {
    }

    private final BankService bank;
    private final EventLog events;

    private final Map<String, VirtualDevice> devices = new LinkedHashMap<>();
    private final List<Edge> edges = List.of(
            new Edge("pratyush", "manoj"),
            new Edge("pratyush", "sunita"),
            new Edge("manoj", "ajay"),
            new Edge("sunita", "ajay"),
            new Edge("ajay", "kiran"));
    /** Everything any bridge ever uploaded, kept so the dashboard can stage a replay attack. */
    private final List<MeshPacket> uploaded = new ArrayList<>();

    public MeshSimulator(BankService bank, EventLog events) {
        this.bank = bank;
        this.events = events;
        reset();
    }

    public synchronized void reset() {
        devices.clear();
        add(new VirtualDevice("pratyush", "Pratyush (sender, offline)", 110, 200, false));
        add(new VirtualDevice("manoj", "Manoj", 300, 80, false));
        add(new VirtualDevice("sunita", "Sunita", 300, 320, true));
        add(new VirtualDevice("ajay", "Ajay", 490, 200, true));
        add(new VirtualDevice("kiran", "Kiran", 680, 200, true));
        uploaded.clear();
    }

    private void add(VirtualDevice device) {
        devices.put(device.id(), device);
    }

    public synchronized void inject(String deviceId, MeshPacket packet) {
        device(deviceId).accept(packet);
        events.add("SEND", deviceId + " sealed payment " + shortId(packet.packetId())
                + " and is broadcasting it over Bluetooth (ttl=" + packet.ttl() + ")");
    }

    /**
     * One gossip round: every phone offers every packet it holds to every phone in range.
     * Uses a snapshot so a packet moves at most one hop per round, like real radio broadcasts.
     */
    public synchronized GossipSummary gossipRound() {
        Map<String, List<MeshPacket>> outbox = new HashMap<>();
        for (VirtualDevice d : devices.values()) {
            outbox.put(d.id(), d.held().stream().filter(p -> p.ttl() > 0).toList());
        }

        List<Hop> hops = new ArrayList<>();
        for (Edge e : edges) {
            deliver(e.a(), outbox.get(e.a()), device(e.b()), hops);
            deliver(e.b(), outbox.get(e.b()), device(e.a()), hops);
        }

        int inFlight = devices.values().stream().mapToInt(d -> d.held().size()).sum();
        events.add("GOSSIP", hops.isEmpty()
                ? "Gossip round: nothing new to share"
                : "Gossip round: " + hops.size() + " packet hop(s) over Bluetooth");
        return new GossipSummary(hops.size(), inFlight, hops);
    }

    private void deliver(String fromId, List<MeshPacket> packets, VirtualDevice to, List<Hop> hops) {
        for (MeshPacket p : packets) {
            if (to.accept(p.withTtl(p.ttl() - 1))) {
                hops.add(new Hop(fromId, to.id(), shortId(p.packetId())));
            }
        }
    }

    /**
     * Every phone with internet uploads what it carries, all at once and on separate threads,
     * so several bridges really do race to deliver the same packet. Idempotency sorts it out.
     */
    public List<IngestResult> flushBridges() throws InterruptedException {
        List<Upload> uploads = new ArrayList<>();
        synchronized (this) {
            for (VirtualDevice d : devices.values()) {
                if (d.hasInternet()) {
                    d.drainHeld().forEach(p -> uploads.add(new Upload(d.id(), p)));
                }
            }
            uploads.forEach(u -> uploaded.add(u.packet()));
        }
        if (uploads.isEmpty()) {
            events.add("BRIDGE", "No bridge is holding any packet yet");
            return List.of();
        }

        CountDownLatch startGun = new CountDownLatch(1);
        List<Future<IngestResult>> futures = new ArrayList<>();
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (Upload u : uploads) {
                futures.add(pool.submit(() -> {
                    startGun.await();
                    return bank.ingest(u.packet(), u.bridgeId());
                }));
            }
            startGun.countDown();
        }

        List<IngestResult> results = new ArrayList<>();
        for (Future<IngestResult> f : futures) {
            try {
                IngestResult r = f.get();
                results.add(r);
                events.add(r.outcome() == Outcome.SETTLED ? "BANK" : "BANK-DROP",
                        r.bridgeId() + " uploaded " + shortId(r.packetId()) + " → " + r.outcome());
            } catch (ExecutionException e) {
                events.add("ERROR", "Upload failed: " + e.getCause());
            }
        }
        return results;
    }

    public synchronized boolean toggleInternet(String deviceId) {
        VirtualDevice d = device(deviceId);
        d.setInternet(!d.hasInternet());
        events.add("NET", d.id() + (d.hasInternet() ? " got mobile data - now a bridge" : " lost mobile data"));
        return d.hasInternet();
    }

    public synchronized Optional<MeshPacket> lastUploaded() {
        return uploaded.isEmpty() ? Optional.empty() : Optional.of(uploaded.get(uploaded.size() - 1));
    }

    public synchronized List<DeviceView> devices() {
        return devices.values().stream()
                .map(d -> new DeviceView(d.id(), d.owner(), d.x(), d.y(), d.hasInternet(),
                        d.held().stream().map(p -> shortId(p.packetId())).toList()))
                .toList();
    }

    public List<Edge> edges() {
        return edges;
    }

    public synchronized boolean hasDevice(String id) {
        return devices.containsKey(id);
    }

    private VirtualDevice device(String id) {
        VirtualDevice d = devices.get(id);
        if (d == null) {
            throw new NoSuchElementException("No device '" + id + "'");
        }
        return d;
    }

    public static String shortId(String packetId) {
        return packetId.substring(0, 8);
    }
}
