package com.finsight.mcp.config;

import com.finsight.adapter.obp.ObpOpenBankingAdapter;
import com.finsight.adapter.pgvector.PgVectorFraudAdapter;
import com.finsight.adapter.stripe.StripePaymentAdapter;
import com.finsight.core.service.FraudService;
import com.finsight.core.service.OpenBankingService;
import com.finsight.core.service.PaymentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Full production stack — all real adapters.
 * Stripe payments + pgvector AI fraud scoring + OBP open banking.
 *
 * Requires:
 *   - STRIPE_API_KEY env var
 *   - OBP_CONSUMER_KEY, OBP_USERNAME, OBP_PASSWORD env vars
 *   - Ollama running with nomic-embed-text model
 */
@Configuration
@Profile("prod")
public class ProdServicesConfig {

    @Bean
    public PaymentService paymentService(StripePaymentAdapter adapter) {
        return new PaymentService(adapter);
    }

    @Bean
    public FraudService fraudService(PgVectorFraudAdapter adapter) {
        return new FraudService(adapter);
    }

    @Bean
    public OpenBankingService openBankingService(ObpOpenBankingAdapter adapter) {
        return new OpenBankingService(adapter);
    }
}