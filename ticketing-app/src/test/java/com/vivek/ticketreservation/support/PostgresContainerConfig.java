package com.vivek.ticketreservation.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.PostgreSQLContainer;

/**
 * Real PostgreSQL for integration tests, same major version as docker-compose.yml.
 *
 * <p>{@code @ServiceConnection} points the datasource at the container, so no URLs are hard-coded.
 * Spring caches the test context, so all integration tests share one container per JVM run.
 */
@TestConfiguration(proxyBeanMethods = false)
public class PostgresContainerConfig {

    @Bean
    @ServiceConnection
    PostgreSQLContainer<?> postgres() {
        return new PostgreSQLContainer<>("postgres:16-alpine");
    }
}
