package com.vivek.ticketreservation.inventory.application.port;

import java.util.List;
import java.util.UUID;

/**
 * Outbound port: seat transitions driven by a hold (confirm, release). Claiming is the
 * {@link SeatLockingStrategy}'s job.
 *
 * <p>Every write is conditional on {@code hold_id = :holdId AND status = 'HELD'}: ownership by hold id,
 * as in {@code domain.ShowSeat}, so a stale actor never moves a seat another hold has since claimed.
 */
public interface ShowSeatStore {

    /** HELD → BOOKED for the seats this hold still owns. @return number of seats booked. */
    int bookSeatsOf(UUID holdId);

    /** HELD → AVAILABLE for the seats this hold still owns. @return number of seats released. */
    int releaseSeatsOf(UUID holdId);

    /** Seat codes with their frozen prices (BR-05), in seat-code order. */
    List<PricedSeat> prices(UUID showId, List<String> seatCodes);

    record PricedSeat(String seatCode, long priceMinor) { }
}
