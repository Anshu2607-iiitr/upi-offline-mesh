package com.demo.upimesh.mesh;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;

/** Human-readable trail of what happened on the mesh, newest first, for the dashboard. */
@Component
public class EventLog {

    public record Event(Instant at, String kind, String message) {
    }

    private static final int LIMIT = 200;

    private final ConcurrentLinkedDeque<Event> events = new ConcurrentLinkedDeque<>();
    private final Clock clock;

    public EventLog(Clock clock) {
        this.clock = clock;
    }

    public void add(String kind, String message) {
        events.addFirst(new Event(clock.instant(), kind, message));
        while (events.size() > LIMIT) {
            events.pollLast();
        }
    }

    public List<Event> recent(int limit) {
        return events.stream().limit(limit).toList();
    }

    public void clear() {
        events.clear();
    }
}
