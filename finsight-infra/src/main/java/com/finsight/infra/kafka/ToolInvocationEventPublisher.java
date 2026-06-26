package com.finsight.infra.kafka;

import com.finsight.core.event.FinsightTopics;
import com.finsight.core.event.ToolInvocationEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes ToolInvocationEvent to Kafka.
 * Active on all non-mock profiles.
 *
 * Uses async send — we never want Kafka latency to slow down
 * the MCP tool response. Fire and forget with error logging.
 */
@Component
@Profile("!mock")
@RequiredArgsConstructor
@Slf4j
public class ToolInvocationEventPublisher {

    private final KafkaTemplate<String, ToolInvocationEvent> kafkaTemplate;

    public void publish(ToolInvocationEvent event) {
        CompletableFuture<SendResult<String, ToolInvocationEvent>> future =
                kafkaTemplate.send(
                        FinsightTopics.TOOL_INVOCATIONS,
                        event.tenantId(),   // partition key — same tenant goes to same partition
                        event
                );

        future.whenComplete((result, ex) -> {
            if (ex != null) {
                // Never let Kafka failures break the main flow — just log
                log.error("[KAFKA] Failed to publish ToolInvocationEvent tool={} tenant={}: {}",
                        event.toolName(), event.tenantId(), ex.getMessage());
            } else {
                log.debug("[KAFKA] Published tool={} tenant={} offset={}",
                        event.toolName(), event.tenantId(),
                        result.getRecordMetadata().offset());
            }
        });
    }
}