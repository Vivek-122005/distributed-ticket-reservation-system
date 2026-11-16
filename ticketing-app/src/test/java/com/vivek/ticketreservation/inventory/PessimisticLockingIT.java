package com.vivek.ticketreservation.inventory;

import org.springframework.test.context.TestPropertySource;

/** Runs the {@link SeatLockingContract} against the pessimistic strategy. */
@TestPropertySource(properties = "inventory.locking.strategy=pessimistic")
class PessimisticLockingIT extends SeatLockingContract { }
