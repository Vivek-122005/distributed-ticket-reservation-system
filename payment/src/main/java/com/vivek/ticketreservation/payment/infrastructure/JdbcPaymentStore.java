package com.vivek.ticketreservation.payment.infrastructure;

import com.vivek.ticketreservation.payment.application.port.PaymentStore;
import com.vivek.ticketreservation.payment.domain.Payment;
import com.vivek.ticketreservation.payment.domain.PaymentStatus;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** JDBC adapter of {@link PaymentStore} over {@code payment.payments}. Pattern: Repository. */
@Repository
class JdbcPaymentStore implements PaymentStore {
    private static final String SELECT = """
            SELECT id, booking_id, user_id, amount_minor, currency, status, gateway, gateway_order_id, failure_reason,
                   created_at, updated_at, version FROM payment.payments""";
    private static final RowMapper<Payment> ROW = (rs, n) -> new Payment(rs.getObject("id", UUID.class),
            rs.getObject("booking_id", UUID.class), rs.getObject("user_id", UUID.class), rs.getLong("amount_minor"),
            rs.getString("currency"), PaymentStatus.valueOf(rs.getString("status")), rs.getString("gateway"),
            rs.getString("gateway_order_id"), rs.getString("failure_reason"), rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"));

    private final NamedParameterJdbcTemplate jdbc;

    JdbcPaymentStore(NamedParameterJdbcTemplate jdbc) { this.jdbc = jdbc; }

    @Override
    public Payment insertIfAbsent(Payment p) {
        jdbc.update("""
                INSERT INTO payment.payments (id, booking_id, user_id, amount_minor, currency, status, gateway, gateway_order_id)
                VALUES (:id, :booking, :user, :amount, :currency, :status, :gateway, :order)
                ON CONFLICT (booking_id) DO NOTHING""",
                new MapSqlParameterSource().addValue("id", p.id()).addValue("booking", p.bookingId())
                        .addValue("user", p.userId()).addValue("amount", p.amountMinor()).addValue("currency", p.currency())
                        .addValue("status", p.status().name()).addValue("gateway", p.gateway()).addValue("order", p.gatewayOrderId()));
        return findByBooking(p.bookingId()).orElseThrow();
    }

    @Override
    public Optional<Payment> find(UUID paymentId) { return one(SELECT + " WHERE id = :v", paymentId); }

    @Override
    public Optional<Payment> findByBooking(UUID bookingId) { return one(SELECT + " WHERE booking_id = :v", bookingId); }

    @Override
    public Optional<Payment> findByOrder(String gatewayOrderId) { return one(SELECT + " WHERE gateway_order_id = :v", gatewayOrderId); }

    @Override
    public List<Payment> findByBookings(Collection<UUID> bookingIds) {
        return jdbc.query(SELECT + " WHERE booking_id IN (:ids)", new MapSqlParameterSource("ids", bookingIds), ROW);
    }

    @Override
    public boolean update(Payment p) {
        return jdbc.update("""
                UPDATE payment.payments SET status = :status, failure_reason = :reason, version = version + 1, updated_at = now()
                WHERE id = :id AND version = :version""",
                new MapSqlParameterSource().addValue("status", p.status().name()).addValue("reason", p.failureReason())
                        .addValue("id", p.id()).addValue("version", p.version())) == 1;
    }

    @Override
    public List<Payment> findUnresolved(Duration olderThan, int limit) {
        return jdbc.query(SELECT + " " + """
                WHERE status IN ('PROCESSING', 'UNKNOWN') AND updated_at < now() - make_interval(secs => :age)
                ORDER BY updated_at LIMIT :limit""",
                new MapSqlParameterSource().addValue("age", olderThan.toMillis() / 1000.0).addValue("limit", limit), ROW);
    }

    private Optional<Payment> one(String sql, Object value) {
        return jdbc.query(sql, new MapSqlParameterSource("v", value), ROW).stream().findFirst();
    }
}
