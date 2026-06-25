package com.finsight.mcp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication(scanBasePackages = "com.finsight")
@ConfigurationPropertiesScan("com.finsight")
public class FinsightMcpApplication {

   public static void main(String[] args) {
        SpringApplication.run(FinsightMcpApplication.class, args);
    }
}