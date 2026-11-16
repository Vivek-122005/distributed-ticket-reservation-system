package com.vivek.ticketreservation.support;

import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * SQL checks of the plan's core invariants (§2.3) on the inventory schema. Run after every
 * contention test; the load tests (P9.2) reuse the same queries.
 */
public final class InventoryInvariants {

    private InventoryInvariants() { }

    public static void assertHold(JdbcTemplate jdbc) {
        // Invariant 1: a seat is held by at most one live hold. Each show seat row has a single hold_id,
        // so the real risk is a hold that *believes* it owns a seat another hold has: every live hold
        // must own every one of its seat codes.
        List<String> brokenLiveHolds = jdbc.queryForList("""
                SELECT h.id::text FROM inventory.holds h
                 WHERE h.status = 'HELD' AND h.expires_at >= now()
                   AND cardinality(h.seat_codes) <> (SELECT count(*) FROM inventory.show_seats ss
                                                      WHERE ss.hold_id = h.id AND ss.status = 'HELD')
                """, String.class);
        assertThat(brokenLiveHolds).as("live holds that do not own all their seats (partial hold / oversell)").isEmpty();

        // Invariant 2: a confirmed hold owns all its seats as BOOKED.
        List<String> brokenConfirmed = jdbc.queryForList("""
                SELECT h.id::text FROM inventory.holds h
                 WHERE h.status = 'CONFIRMED'
                   AND cardinality(h.seat_codes) <> (SELECT count(*) FROM inventory.show_seats ss
                                                      WHERE ss.hold_id = h.id AND ss.status = 'BOOKED')
                """, String.class);
        assertThat(brokenConfirmed).as("confirmed holds missing booked seats").isEmpty();

        // No seat is HELD or BOOKED for a hold that does not claim it, or for a released hold.
        Integer orphans = jdbc.queryForObject("""
                SELECT count(*) FROM inventory.show_seats ss
                  JOIN inventory.holds h ON h.id = ss.hold_id
                 WHERE NOT (ss.seat_code = ANY(h.seat_codes))
                    OR (ss.status = 'BOOKED' AND h.status <> 'CONFIRMED')
                    OR (ss.status = 'HELD' AND h.status NOT IN ('HELD'))
                """, Integer.class);
        assertThat(orphans).as("seats owned by a hold that does not account for them").isZero();
    }
}
