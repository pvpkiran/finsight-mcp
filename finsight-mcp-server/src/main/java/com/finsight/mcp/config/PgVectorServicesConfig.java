package com.finsight.mcp.config;

import com.finsight.adapter.mock.banking.MockOpenBankingAdapter;
import com.finsight.adapter.mock.payment.MockPaymentAdapter;
import com.finsight.adapter.pgvector.PgVectorFraudAdapter;
import com.finsight.core.service.FraudService;
import com.finsight.core.service.OpenBankingService;
import com.finsight.core.service.PaymentService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("pgvector & !stripe")
public class PgVectorServicesConfig {

    @Bean
    public PaymentService paymentService(MockPaymentAdapter adapter) {
        return new PaymentService(adapter);
    }

    @Bean
    public FraudService fraudService(PgVectorFraudAdapter adapter) {
        return new FraudService(adapter);
    }

    @Bean
    public OpenBankingService openBankingService(MockOpenBankingAdapter adapter) {
        return new OpenBankingService(adapter);
    }
}