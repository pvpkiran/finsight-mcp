package com.finsight.mcp.config;

import com.finsight.mcp.tools.FraudTools;
import com.finsight.mcp.tools.OpenBankingTools;
import com.finsight.mcp.tools.PaymentTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers all MCP tool beans with the Spring AI MCP server.
 *
 * Spring AI scans for @Tool annotations automatically when beans
 * are registered via ToolCallbackProvider.
 */
@Configuration
public class McpToolsConfig {

    @Bean
    public ToolCallbackProvider paymentToolCallbacks(PaymentTools paymentTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(paymentTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider fraudToolCallbacks(FraudTools fraudTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(fraudTools)
                .build();
    }

    @Bean
    public ToolCallbackProvider openBankingToolCallbacks(OpenBankingTools openBankingTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(openBankingTools)
                .build();
    }
}