package com.finsight.mcp;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.KafkaContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

/**
 * Base class for all FinSight integration tests.
 *
 * Starts three containers once for the entire test suite (static fields):
 *   - PostgreSQL 16 with pgvector extension
 *   - Redis 7
 *   - Kafka (Confluent Platform)
 *
 * Uses @DynamicPropertySource to override Spring Boot datasource,
 * Redis, and Kafka properties with container ports.
 *
 * All IT classes extend this — containers are shared across test classes
 * via static fields, so they start once and stay up for the full suite.
 * This makes the test suite fast (~10s startup vs ~30s per class).
 *
 * Profile: "local" — activates real infra adapters (PostgresAuditAdapter,
 * RedisIdempotencyAdapter) so we test the real production code paths.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("local")
@Testcontainers
public abstract class AbstractIntegrationTest {

    // ── PostgreSQL with pgvector ───────────────────────────────
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>(DockerImageName.parse("pgvector/pgvector:pg16"))
                    .withDatabaseName("finsight_test")
                    .withUsername("finsight")
                    .withPassword("finsight_secret")
                    .withReuse(true);

    // ── Redis ─────────────────────────────────────────────────
    static final GenericContainer<?> redis =
            new GenericContainer<>(DockerImageName.parse("redis:7-alpine"))
                    .withExposedPorts(6379)
                    .withCommand("redis-server", "--requirepass", "redis_secret")
                    .withReuse(true);

    // ── Kafka ─────────────────────────────────────────────────
    static final KafkaContainer kafka =
            new KafkaContainer(DockerImageName.parse("confluentinc/cp-kafka:7.6.0"))
                    .withReuse(true);

    static {
        System.setProperty("DOCKER_HOST", "unix:///var/run/docker.sock");
        System.setProperty("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock");

        // Start all containers in parallel for faster startup
        postgres.start();
        redis.start();
        kafka.start();
    }

    /**
     * Override Spring Boot properties with container connection details.
     * Called before the Spring context is created.
     */
    @DynamicPropertySource
    static void overrideProperties(DynamicPropertyRegistry registry) {
        // PostgreSQL
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);

        // Redis
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port",
                () -> redis.getMappedPort(6379).toString());
        registry.add("spring.data.redis.password", () -> "redis_secret");

        // Kafka
        registry.add("spring.kafka.bootstrap-servers", kafka::getBootstrapServers);

        // Disable Keycloak JWT validation in tests — use mock security
        registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri",
                () -> "");
        registry.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> "http://localhost:9999/.well-known/jwks.json");
    }
}