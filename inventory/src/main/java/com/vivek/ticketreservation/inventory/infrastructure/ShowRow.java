package com.vivek.ticketreservation.inventory.infrastructure;

import jakarta.persistence.*;
import java.util.UUID;

/** Read-only view of {@code inventory.shows} needed by the hold use case (sale status). */
@Entity
@Table(name = "shows", schema = "inventory")
public class ShowRow {
    @Id private UUID id;
    @Column(name = "status", nullable = false) private String status;
    protected ShowRow() { }
    public UUID getId() { return id; }
    public boolean isOnSale() { return "ON_SALE".equals(status); }
}
