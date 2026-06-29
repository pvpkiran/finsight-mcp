package com.finsight.mcp.config;

import com.finsight.adapter.mock.fraud.MockFraudAdapter;
import com.finsight.adapter.mock.payment.MockPaymentAdapter;
import com.finsight.adapter.obp.ObpOpenBankingAdapter;
import com.finsight.core.service.FraudService;
import com.finsight.core.service.OpenBankingService;
import com.finsight.core.service.PaymentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Service wiring for 'obp' profile.
 * Real OBP open banking adapter, mock payment and fraud.
 */
@Configuration
@Profile("obp & !stripe & !pgvector")
public class ObpServicesConfig {

    @Bean
    public PaymentService paymentService(MockPaymentAdapter adapter) {
        return new PaymentService(adapter);
    }

    @Bean
    public FraudService fraudService(MockFraudAdapter adapter) {
        return new FraudService(adapter);
    }

    @Bean
    public OpenBankingService openBankingService(ObpOpenBankingAdapter adapter) {
        return new OpenBankingService(adapter);
    }
}