package com.finsight.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.boot.hibernate.autoconfigure.HibernateJpaAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;

@SpringBootApplication(
        scanBasePackages = "com.finsight",
        exclude = {
                DataSourceAutoConfiguration.class,
                HibernateJpaAutoConfiguration.class,
                DataRedisAutoConfiguration.class,
                KafkaAutoConfiguration.class,
        }
)
@ConfigurationPropertiesScan("com.finsight")
public class FinsightMcpApplication {

   public static void main(String[] args) {
        SpringApplication.run(FinsightMcpApplication.class, args);
    }
}