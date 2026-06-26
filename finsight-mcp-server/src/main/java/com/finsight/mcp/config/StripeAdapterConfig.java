package com.finsight.mcp.config;

import com.finsight.adapter.mock.banking.MockOpenBankingAdapter;
import com.finsight.adapter.mock.fraud.MockFraudAdapter;
import com.finsight.adapter.stripe.StripePaymentAdapter;
import com.finsight.core.service.FraudService;
import com.finsight.core.service.OpenBankingService;
import com.finsight.core.service.PaymentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("stripe")
@ComponentScan(basePackages = "com.finsight.adapter.stripe")
public class StripeAdapterConfig {

    @Bean
    public PaymentService paymentService(StripePaymentAdapter adapter) {
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