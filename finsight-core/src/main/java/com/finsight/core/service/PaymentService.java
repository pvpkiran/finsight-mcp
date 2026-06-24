package com.finsight.core.service;

import com.finsight.core.domain.model.PaymentRoute;
import com.finsight.core.domain.model.Transaction;
import com.finsight.core.domain.valueobject.TenantId;
import com.finsight.core.domain.valueobject.TransactionId;
import com.finsight.core.exception.FinsightDomainException;
import com.finsight.core.port.PaymentPort;

import java.util.List;

/**
 * Domain service for payment intelligence operations.
 * Contains business logic — no Spring annotations, no framework coupling.
 * Depends only on PaymentPort (interface) — never on any adapter.
 *
 * This class is the inbound port from the MCP tool layer's perspective.
 * MCP tools call this service; this service calls PaymentPort adapters.
 */
public class PaymentService {

    private final PaymentPort paymentPort;

    public PaymentService(PaymentPort paymentPort) {
        this.paymentPort = paymentPort;
    }

    /**
     * Analyse the optimal payment route for a transaction.
     * Called by the analyzePaymentRoute MCP tool.
     */
    public PaymentRoute analyzeRoute(PaymentPort.RouteRequest request) {
        return paymentPort.analyzeRoute(request);
    }

    /**
     * Fetch and return a transaction, throwing a typed exception if not found.
     * Called by multiple MCP tools that need transaction context.
     */
    public Transaction getTransaction(String transactionId, TenantId tenantId) {
        return paymentPort
                .findTransaction(TransactionId.of(transactionId), tenantId)
                .orElseThrow(() -> new FinsightDomainException
                        .TransactionNotFoundException(transactionId));
    }

    /**
     * Explain why a payment was declined in human-readable terms.
     * Called by the explainDecline MCP tool.
     */
    public String explainDecline(String transactionId, TenantId tenantId) {
        Transaction tx = getTransaction(transactionId, tenantId);
        if (!tx.isDeclined()) {
            return "Transaction %s was not declined — current status: %s"
                    .formatted(transactionId, tx.status());
        }
        return paymentPort.explainDecline(TransactionId.of(transactionId), tenantId);
    }

    /**
     * Fetch transactions for reconciliation.
     * Called by the reconcileTransaction MCP tool.
     */
    public List<Transaction> fetchForReconciliation(TenantId tenantId,
                                                    PaymentPort.TransactionFilter filter) {
        return paymentPort.fetchTransactions(tenantId, filter);
    }
}