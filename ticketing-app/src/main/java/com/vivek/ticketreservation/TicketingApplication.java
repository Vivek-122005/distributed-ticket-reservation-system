package com.vivek.ticketreservation;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Stage-1 deployable (ADR 002). Lives in the root package so component, entity and repository
 * scanning covers every module ({@code ..inventory}, {@code ..booking}, {@code ..payment}).
 */
@SpringBootApplication
public class TicketingApplication {
    public static void main(String[] args) { SpringApplication.run(TicketingApplication.class, args); }
}
