package com.vivek.ticketreservation.payment.infrastructure;

import com.vivek.ticketreservation.payment.application.port.WebhookInbox;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

/** {@link WebhookInbox} over {@code payment.webhook_events}. */
@Repository
class JdbcWebhookInbox implements WebhookInbox {
    private final NamedParameterJdbcTemplate jdbc;

    JdbcWebhookInbox(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public boolean seen(String eventId) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM payment.webhook_events WHERE event_id = :id",
                new MapSqlParameterSource("id", eventId), Integer.class);
        return n != null && n > 0;
    }

    @Override
    public void record(String eventId, String orderId, String type, String result) {
        jdbc.update("""
                INSERT INTO payment.webhook_events (event_id, order_id, type, result) VALUES (:id, :order, :type, :result)
                ON CONFLICT (event_id) DO NOTHING""",
                new MapSqlParameterSource().addValue("id", eventId).addValue("order", orderId)
                        .addValue("type", type).addValue("result", result));
    }
}
