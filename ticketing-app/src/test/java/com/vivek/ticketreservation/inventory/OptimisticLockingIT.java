package com.vivek.ticketreservation.inventory;

import org.springframework.test.context.TestPropertySource;

/** Runs the {@link SeatLockingContract} against the optimistic strategy. */
@TestPropertySource(properties = "inventory.locking.strategy=optimistic")
class OptimisticLockingIT extends SeatLockingContract { }
