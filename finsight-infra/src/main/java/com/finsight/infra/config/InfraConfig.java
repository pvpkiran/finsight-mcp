package com.finsight.infra.config;

import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * Infrastructure configuration — active on all non-mock profiles.
 * Wires JPA repositories, Redis template, and enables transactions.
 */
@Configuration
@Profile("!mock")
@EnableJpaRepositories(basePackages = "com.finsight.infra")
@EntityScan(basePackages = "com.finsight.infra")
@EnableTransactionManagement
public class InfraConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory factory) {
        return new StringRedisTemplate(factory);
    }
}