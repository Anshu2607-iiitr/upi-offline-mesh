package com.demo.upimesh.mesh;

import com.demo.upimesh.model.MeshPacket;

import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** A phone in the mesh. Guarded by {@link MeshSimulator}'s lock. */
class VirtualDevice {

    private final String id;
    private final String owner;
    private final int x;
    private final int y;
    private boolean hasInternet;

    /** Packets this phone is carrying and will gossip onward. */
    private final Map<String, MeshPacket> held = new LinkedHashMap<>();
    /** Every packet id it has ever accepted, so gossip can't bounce a packet back after upload. */
    private final Set<String> seen = new HashSet<>();

    VirtualDevice(String id, String owner, int x, int y, boolean hasInternet) {
        this.id = id;
        this.owner = owner;
        this.x = x;
        this.y = y;
        this.hasInternet = hasInternet;
    }

    /** @return false if this phone already had the packet (mesh-level dedupe, stops gossip storms) */
    boolean accept(MeshPacket packet) {
        if (!seen.add(packet.packetId())) {
            return false;
        }
        held.put(packet.packetId(), packet);
        return true;
    }

    Collection<MeshPacket> held() {
        return held.values();
    }

    List<MeshPacket> drainHeld() {
        List<MeshPacket> out = List.copyOf(held.values());
        held.clear();
        return out;
    }

    String id() {
        return id;
    }

    String owner() {
        return owner;
    }

    int x() {
        return x;
    }

    int y() {
        return y;
    }

    boolean hasInternet() {
        return hasInternet;
    }

    void setInternet(boolean hasInternet) {
        this.hasInternet = hasInternet;
    }
}
