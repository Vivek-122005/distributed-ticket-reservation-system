package com.vivek.ticketreservation.inventory.application.port;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Outbound port: seat transitions driven by a hold (confirm, release, expiry). Claiming is the
 * {@link SeatLockingStrategy}'s job.
 *
 * <p>Every write is conditional on {@code hold_id = :holdId AND status = 'HELD'}: ownership by hold id,
 * as in {@code domain.ShowSeat}, so a stale actor never moves a seat another hold has since claimed.
 * Writes return each changed seat with its new row version, for {@code SeatsChanged} events (P2.3).
 */
public interface ShowSeatStore {

    /** HELD → BOOKED for the seats this hold still owns. */
    List<SeatVersion> bookSeatsOf(UUID holdId);

    /**
     * HELD → AVAILABLE for every seat these holds still own, in one batch.
     * @return released seats per hold id (holds that own no seat any more are absent)
     */
    Map<UUID, List<SeatVersion>> releaseSeatsOfHolds(List<UUID> holdIds);

    /** Seat codes with their frozen prices (BR-05), in seat-code order. */
    List<PricedSeat> prices(UUID showId, List<String> seatCodes);

    record PricedSeat(String seatCode, long priceMinor) { }

    /** A seat that changed, with its row version after the change. */
    record SeatVersion(String seatCode, long version) { }
}
