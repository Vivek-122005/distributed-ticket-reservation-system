package com.vivek.ticketreservation.inventory;

import org.springframework.test.context.TestPropertySource;

/** Runs the {@link SeatLockingContract} against the conditional-update strategy. */
@TestPropertySource(properties = "inventory.locking.strategy=conditional-update")
class ConditionalUpdateLockingIT extends SeatLockingContract { }
