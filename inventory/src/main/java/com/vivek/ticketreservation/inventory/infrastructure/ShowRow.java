package com.vivek.ticketreservation.inventory.infrastructure;

import jakarta.persistence.*;
import java.util.UUID;

/** Read-only view of {@code inventory.shows} needed by the hold use case (sale status, currency). */
@Entity
@Table(name = "shows", schema = "inventory")
public class ShowRow {
    @Id private UUID id;
    @Column(name = "status", nullable = false) private String status;
    @Column(name = "currency", nullable = false, columnDefinition = "bpchar(3)") private String currency;
    protected ShowRow() { }
    public UUID getId() { return id; }
    public boolean isOnSale() { return "ON_SALE".equals(status); }
    public String getCurrency() { return currency; }
}
