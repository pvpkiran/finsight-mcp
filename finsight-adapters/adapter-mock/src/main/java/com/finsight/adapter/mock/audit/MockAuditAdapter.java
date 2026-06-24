package com.finsight.adapter.mock.audit;

import com.finsight.core.port.AuditPort;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * In-memory audit adapter for mock profile.
 * Stores events in a thread-safe list — inspectable in tests.
 */
@Component
@Profile("mock")
public class MockAuditAdapter implements AuditPort {

    private final List<AuditEvent> events = Collections.synchronizedList(new ArrayList<>());

    @Override
    public void record(AuditEvent event) {
        events.add(event);
        // Log to console in mock mode so you can see tool invocations during dev
        System.out.printf("[AUDIT] tool=%s tenant=%s status=%s duration=%dms traceId=%s%n",
                event.toolName(),
                event.tenantId(),
                event.status(),
                event.durationMs() != null ? event.durationMs() : 0,
                event.traceId());
    }

    /** Expose recorded events for test assertions. */
    public List<AuditEvent> getRecordedEvents() {
        return List.copyOf(events);
    }

    public void clear() {
        events.clear();
    }
}