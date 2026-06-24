package com.finsight.mcp.tools;

import com.finsight.core.domain.model.PaymentRoute;
import com.finsight.core.domain.model.Transaction;
import com.finsight.core.domain.valueobject.Money;
import com.finsight.core.port.PaymentPort;
import com.finsight.core.service.PaymentService;
import com.finsight.mcp.security.TenantContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * MCP tools for the payments intelligence domain.
 *
 * Each method annotated with @Tool becomes an MCP tool
 * that any LLM agent can discover and call via the MCP protocol.
 *
 * Security: @PreAuthorize checks the OAuth2 scope from the JWT.
 * Tenant: TenantContext.require() extracts tenant from the validated JWT.
 */
@Component
public class PaymentTools {

    private final PaymentService paymentService;

    public PaymentTools(PaymentService paymentService) {
        this.paymentService = paymentService;
    }

    @Tool(name = "analyzePaymentRoute",
            description = """
              Analyse the optimal payment routing for a transaction.
              Returns the recommended acquirer, PSP, estimated fee, success probability,
              and alternative routes with trade-offs.
              Use this when asked about payment routing, acquirer selection, or fee optimisation.
              """)
    @PreAuthorize("hasAuthority('SCOPE_payment:read')")
    public PaymentRouteResult analyzePaymentRoute(
            @ToolParam(description = "Transaction amount as a decimal string, e.g. '250.00'")
            String amount,
            @ToolParam(description = "ISO 4217 currency code, e.g. 'EUR', 'GBP', 'USD'")
            String currency,
            @ToolParam(description = "Merchant ID to route for")
            String merchantId,
            @ToolParam(description = "ISO 3166-1 alpha-2 country code, e.g. 'DE', 'GB'")
            String countryCode,
            @ToolParam(description = "Payment method: CARD, BANK_TRANSFER, or OPEN_BANKING")
            String paymentMethod
    ) {
        var tenantId = TenantContext.require();
        var request = new PaymentPort.RouteRequest(
                Money.of(new BigDecimal(amount), currency),
                merchantId, countryCode, paymentMethod, tenantId);

        PaymentRoute route = paymentService.analyzeRoute(request);

        return new PaymentRouteResult(
                route.recommendedAcquirer(),
                route.recommendedPsp(),
                route.successProbability(),
                route.estimatedFee().toString(),
                route.routingReasons(),
                route.alternatives().stream()
                        .map(a -> "%s via %s (%.0f%% success, fee: %s) — %s"
                                .formatted(a.acquirerId(), a.pspId(),
                                        a.successProbability() * 100,
                                        a.estimatedFee(), a.tradeOff()))
                        .toList(),
                route.declineRiskReason()
        );
    }

    @Tool(name = "explainDecline",
            description = """
              Explain why a specific payment transaction was declined.
              Returns a human-readable explanation of the decline reason,
              including the decline code and recommended next steps.
              Use this when asked why a payment failed or was rejected.
              """)
    @PreAuthorize("hasAuthority('SCOPE_payment:read')")
    public String explainDecline(
            @ToolParam(description = "The transaction ID to explain, e.g. 'txn-002'")
            String transactionId
    ) {
        var tenantId = TenantContext.require();
        return paymentService.explainDecline(transactionId, tenantId);
    }

    @Tool(name = "getTransaction",
            description = """
              Fetch details of a specific payment transaction by ID.
              Returns amount, status, merchant, acquirer, timestamps, and decline code if applicable.
              Use this when asked about a specific transaction's details or current status.
              """)
    @PreAuthorize("hasAuthority('SCOPE_payment:read')")
    public TransactionResult getTransaction(
            @ToolParam(description = "The transaction ID, e.g. 'txn-001'")
            String transactionId
    ) {
        var tenantId = TenantContext.require();
        Transaction tx = paymentService.getTransaction(transactionId, tenantId);
        return TransactionResult.from(tx);
    }

    @Tool(name = "reconcileTransactions",
            description = """
              Fetch recent transactions for reconciliation within a time window.
              Returns a list of transactions with their statuses.
              Use this when asked to reconcile payments, find failed transactions,
              or get a summary of recent payment activity.
              """)
    @PreAuthorize("hasAuthority('SCOPE_payment:read')")
    public List<TransactionResult> reconcileTransactions(
            @ToolParam(description = "Hours to look back, e.g. 24 for last 24 hours")
            int hoursBack,
            @ToolParam(description = "Filter by status: CAPTURED, DECLINED, AUTHORISED, or ALL")
            String statusFilter
    ) {
        var tenantId = TenantContext.require();
        Transaction.TransactionStatus status = "ALL".equalsIgnoreCase(statusFilter)
                ? null
                : Transaction.TransactionStatus.valueOf(statusFilter.toUpperCase());

        var filter = new PaymentPort.TransactionFilter(
                Instant.now().minus(hoursBack, ChronoUnit.HOURS),
                Instant.now(),
                status,
                100
        );

        return paymentService.fetchForReconciliation(tenantId, filter)
                .stream()
                .map(TransactionResult::from)
                .toList();
    }

    // ── Result records returned to the LLM ────────────────────────────

    public record PaymentRouteResult(
            String recommendedAcquirer,
            String recommendedPsp,
            double successProbability,
            String estimatedFee,
            List<String> routingReasons,
            List<String> alternatives,
            String declineRiskWarning
    ) {}

    public record TransactionResult(
            String id,
            String amount,
            String status,
            String merchantName,
            String paymentMethod,
            String acquirerId,
            String countryCode,
            String declineCode,
            String createdAt
    ) {
        public static TransactionResult from(Transaction tx) {
            return new TransactionResult(
                    tx.id().value(),
                    tx.amount().toString(),
                    tx.status().name(),
                    tx.merchantName(),
                    tx.paymentMethod(),
                    tx.acquirerId(),
                    tx.countryCode(),
                    tx.declineCode(),
                    tx.createdAt().toString()
            );
        }
    }
}