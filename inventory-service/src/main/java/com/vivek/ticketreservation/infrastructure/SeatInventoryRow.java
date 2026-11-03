package com.vivek.ticketreservation.infrastructure;

import jakarta.persistence.*;
import java.util.UUID;

@Entity
@Table(name = "seat_inventory")
public class SeatInventoryRow {
    @Id private UUID id;
    @Column(name = "event_id") private UUID eventId;
    @Column(name = "seat_code") private String seatCode;
    protected SeatInventoryRow() { }
}
