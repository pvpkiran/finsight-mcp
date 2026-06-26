package com.finsight.adapter.mock.config;

import com.finsight.adapter.mock.fraud.MockFraudAdapter;
import com.finsight.adapter.mock.banking.MockOpenBankingAdapter;
import com.finsight.adapter.mock.payment.MockPaymentAdapter;
import com.finsight.core.service.FraudService;
import com.finsight.core.service.OpenBankingService;
import com.finsight.core.service.PaymentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Wires domain services with mock adapters.
 * Active only under the "mock" Spring profile.
 *
 * This is the only place in the codebase where domain services
 * are coupled to a specific adapter implementation.
 * Swap profile → different config class → different adapters.
 */
@Configuration
@Profile("!stripe")
public class MockAdapterConfig {

    @Bean
    public PaymentService paymentService(MockPaymentAdapter adapter) {
        return new PaymentService(adapter);
    }

    @Bean
    public FraudService fraudService(MockFraudAdapter adapter) {
        return new FraudService(adapter);
    }

    @Bean
    public OpenBankingService openBankingService(MockOpenBankingAdapter adapter) {
        return new OpenBankingService(adapter);
    }
}