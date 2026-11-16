package com.vivek.ticketreservation.inventory.application;

import com.vivek.ticketreservation.inventory.api.HoldNotFoundException;
import com.vivek.ticketreservation.inventory.api.HoldResult;
import com.vivek.ticketreservation.inventory.application.port.DatabaseClock;
import com.vivek.ticketreservation.inventory.application.port.HoldStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/** Reads one hold of the calling user; reports a lazily expired hold as EXPIRED. */
@Service
public class GetHoldUseCase {
    private final HoldStore holds;
    private final DatabaseClock clock;
    private final HoldViews views;

    GetHoldUseCase(HoldStore holds, DatabaseClock clock, HoldViews views) {
        this.holds = holds; this.clock = clock; this.views = views;
    }

    @Transactional(readOnly = true)
    public HoldResult execute(UUID holdId, UUID userId) {
        return holds.find(holdId).filter(h -> h.belongsTo(userId))
                .map(h -> views.of(h, clock.now()))
                .orElseThrow(() -> new HoldNotFoundException(holdId));
    }
}
