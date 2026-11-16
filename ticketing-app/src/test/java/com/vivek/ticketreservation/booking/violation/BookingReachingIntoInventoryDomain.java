package com.vivek.ticketreservation.booking.violation;

import com.vivek.ticketreservation.inventory.domain.Hold;

/** Deliberate boundary violation, used only by ModuleBoundaryRulesBiteTest. Never in main code. */
class BookingReachingIntoInventoryDomain {
    Hold leaked;
}
