package com.finsight.core.event;

/**
 * Kafka topic names used across FinSight.
 * Centralised here so producers and consumers use the same constants.
 */
public final class FinsightTopics {

    private FinsightTopics() {}

    /** Published when any MCP tool is invoked — success or failure */
    public static final String TOOL_INVOCATIONS = "finsight.tool.invocations";

    /** Published when a fraud score exceeds CRITICAL threshold */
    public static final String FRAUD_ALERTS = "finsight.fraud.alerts";

    /** Published when a payment is initiated via open banking */
    public static final String PAYMENT_EVENTS = "finsight.payment.events";
}