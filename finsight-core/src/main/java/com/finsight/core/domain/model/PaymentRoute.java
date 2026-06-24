package com.finsight.core.domain.model;

import com.finsight.core.domain.valueobject.Money;
import com.finsight.core.domain.valueobject.TransactionId;

import java.util.List;

/**
 * Represents the optimal payment routing decision for a transaction.
 * Returned by the analyzePaymentRoute MCP tool.
 */
public record PaymentRoute(
        TransactionId transactionId,
        String recommendedAcquirer,
        String recommendedPsp,
        double successProbability,          // 0.0 to 1.0
        Money estimatedFee,
        List<RouteOption> alternatives,     // other viable routes, ranked
        List<String> routingReasons,        // human-readable explanation of why this route
        String declineRiskReason            // null if low risk
) {

    /**
     * An alternative routing option with its trade-offs.
     */
    public record RouteOption(
            String acquirerId,
            String pspId,
            double successProbability,
            Money estimatedFee,
            String tradeOff                // e.g. "Lower fee but 5% lower approval rate"
    ) {}
}