package com.vivek.ticketreservation.inventory.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/** A user's temporary reservation of seats of one show. JPA annotations here are SOLID.md deviation #3 (P1.3). */
@Entity
@Table(name = "holds", schema = "inventory")
public class Hold {
    @Id private UUID id;
    @Column(name = "show_id", nullable = false) private UUID showId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private HoldStatus status;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;

    protected Hold() { }
    public Hold(UUID id, UUID showId, UUID userId, Instant expiresAt) {
        this.id = id; this.showId = showId; this.userId = userId; this.expiresAt = expiresAt; this.status = HoldStatus.HELD;
    }
    public UUID getId() { return id; }
    public UUID getShowId() { return showId; }
    public Instant getExpiresAt() { return expiresAt; }
    public HoldStatus getStatus() { return status; }
}
