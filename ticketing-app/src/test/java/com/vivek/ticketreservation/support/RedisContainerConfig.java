package com.vivek.ticketreservation.support;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.containers.GenericContainer;

/** Real Redis 7 for the Redis-backed strategies, same image as docker-compose.yml. */
@TestConfiguration(proxyBeanMethods = false)
public class RedisContainerConfig {

    @Bean
    @ServiceConnection(name = "redis")
    GenericContainer<?> redis() {
        return new GenericContainer<>("redis:7-alpine").withExposedPorts(6379);
    }
}
