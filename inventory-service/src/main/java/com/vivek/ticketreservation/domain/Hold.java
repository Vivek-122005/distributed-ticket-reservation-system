package com.vivek.ticketreservation.domain;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "holds")
public class Hold {
    @Id private UUID id;
    @Column(name = "event_id", nullable = false) private UUID eventId;
    @Column(name = "user_id", nullable = false) private UUID userId;
    @Enumerated(EnumType.STRING) @Column(nullable = false) private HoldStatus status;
    @Column(name = "expires_at", nullable = false) private Instant expiresAt;

    protected Hold() { }
    public Hold(UUID id, UUID eventId, UUID userId, Instant expiresAt) {
        this.id = id; this.eventId = eventId; this.userId = userId; this.expiresAt = expiresAt; this.status = HoldStatus.ACTIVE;
    }
    public UUID getId() { return id; }
    public Instant getExpiresAt() { return expiresAt; }
    public HoldStatus getStatus() { return status; }
}
