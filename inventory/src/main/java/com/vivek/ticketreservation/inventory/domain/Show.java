package com.vivek.ticketreservation.inventory.domain;

import java.util.Objects;
import java.util.UUID;

/** One performance of an event at a venue: the unit that is sold. Framework-free (DIP). */
public final class Show {
    private final UUID id;
    private final String currency;
    private ShowStatus status;

    public Show(UUID id, ShowStatus status, String currency) {
        this.id = Objects.requireNonNull(id);
        this.status = Objects.requireNonNull(status);
        this.currency = Objects.requireNonNull(currency);
    }

    /** BR-03: seats can only be held while the show is ON_SALE. */
    public boolean isOnSale() { return status == ShowStatus.ON_SALE; }

    public void openSale() { moveTo(ShowStatus.ON_SALE); }
    public void closeSale() { moveTo(ShowStatus.CLOSED); }

    private void moveTo(ShowStatus next) {
        if (!status.canTransitionTo(next)) throw new IllegalStateTransitionException("Show " + id, status, next);
        status = next;
    }

    public UUID id() { return id; }
    public ShowStatus status() { return status; }
    public String currency() { return currency; }
}
