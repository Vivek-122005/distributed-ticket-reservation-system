package com.vivek.ticketreservation.inventory.infrastructure;

import jakarta.persistence.*;
import java.util.UUID;

/** Minimal JPA mapping of {@code inventory.show_seats}; writes go through native claim queries. */
@Entity
@Table(name = "show_seats", schema = "inventory")
public class ShowSeatRow {
    @Id private UUID id;
    @Column(name = "show_id") private UUID showId;
    @Column(name = "seat_code") private String seatCode;
    protected ShowSeatRow() { }
}
