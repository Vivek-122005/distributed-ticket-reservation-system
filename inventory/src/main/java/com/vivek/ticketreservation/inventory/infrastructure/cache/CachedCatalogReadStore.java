package com.vivek.ticketreservation.inventory.infrastructure.cache;

import com.vivek.ticketreservation.inventory.api.CatalogQuery.EventView;
import com.vivek.ticketreservation.inventory.api.CatalogQuery.ShowView;
import com.vivek.ticketreservation.inventory.api.SeatMapQuery.SeatMapView;
import com.vivek.ticketreservation.inventory.api.SeatMapQuery.SeatView;
import com.vivek.ticketreservation.inventory.application.port.CatalogReadStore;
import com.vivek.ticketreservation.inventory.domain.SeatStatus;
import com.vivek.ticketreservation.inventory.infrastructure.cache.SeatSnapshots.LayoutSeat;
import com.vivek.ticketreservation.inventory.infrastructure.cache.SeatStateCache.CachedSeat;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * {@link CatalogReadStore} that serves seat maps from Redis (P2.3). Pattern: Decorator over the JDBC
 * store, which still serves everything else and is the fallback.
 *
 * <ul>
 *   <li>Layout (seat id, row, column, price) never changes after a show is created, so it is loaded
 *       once per show and kept in memory.</li>
 *   <li>States come from {@link SeatStateCache}. A show not cached yet is warmed from Postgres on
 *       first read, and that read is answered from the same snapshot.</li>
 *   <li>Any Redis error falls back to Postgres for that request ({@code seat_map_reads_total{source}}),
 *       so the read path degrades in speed, not in availability.</li>
 * </ul>
 * May lag Postgres by the async update delay; a stale "available" only causes a fast 409 on hold.
 */
class CachedCatalogReadStore implements CatalogReadStore {
    private static final Logger log = LoggerFactory.getLogger(CachedCatalogReadStore.class);
    private final CatalogReadStore database;
    private final SeatStateCache cache;
    private final SeatSnapshots snapshots;
    private final Map<UUID, List<LayoutSeat>> layouts = new ConcurrentHashMap<>();
    private final Counter fromRedis;
    private final Counter fromWarmup;
    private final Counter fromDatabase;

    CachedCatalogReadStore(CatalogReadStore database, SeatStateCache cache, SeatSnapshots snapshots, MeterRegistry registry) {
        this.database = database; this.cache = cache; this.snapshots = snapshots;
        this.fromRedis = counter(registry, "redis");
        this.fromWarmup = counter(registry, "warmup");
        this.fromDatabase = counter(registry, "database");
    }

    private static Counter counter(MeterRegistry registry, String source) {
        return Counter.builder("seat.map.reads").description("Seat map reads by source").tag("source", source).register(registry);
    }

    @Override public List<EventView> publishedEvents(int offset, int limit) { return database.publishedEvents(offset, limit); }
    @Override public long countPublishedEvents() { return database.countPublishedEvents(); }
    @Override public Optional<List<ShowView>> showsOfPublishedEvent(UUID eventId) { return database.showsOfPublishedEvent(eventId); }

    @Override
    public Optional<SeatMapView> seatMap(UUID showId, Optional<String> section) {
        List<LayoutSeat> layout = layouts.computeIfAbsent(showId, snapshots::layout);
        if (layout.isEmpty()) {
            layouts.remove(showId);   // unknown show (or no seats): don't cache a miss
            return database.seatMap(showId, section);
        }
        try {
            Optional<Map<String, CachedSeat>> cached = cache.read(showId);
            Map<String, CachedSeat> states;
            if (cached.isPresent()) {
                states = cached.get();
                fromRedis.increment();
            } else {
                List<CachedSeat> snapshot = snapshots.states(showId);
                cache.warm(showId, snapshot);
                states = snapshot.stream().collect(Collectors.toMap(CachedSeat::seatCode, Function.identity()));
                fromWarmup.increment();
            }
            return Optional.of(view(showId, layout, states, section));
        } catch (RuntimeException redisDown) {
            fromDatabase.increment();
            log.debug("Seat map of {} served from Postgres: {}", showId, redisDown.getMessage());
            return database.seatMap(showId, section);
        }
    }

    private static SeatMapView view(UUID showId, List<LayoutSeat> layout, Map<String, CachedSeat> states, Optional<String> section) {
        Instant now = Instant.now();
        List<SeatView> seats = layout.stream()
                .filter(seat -> section.isEmpty() || seat.section().equals(section.get()))
                .map(seat -> {
                    CachedSeat state = states.get(seat.seatCode());
                    SeatStatus status = state == null ? SeatStatus.AVAILABLE : state.effectiveStatus(now);
                    return new SeatView(seat.seatId(), seat.seatCode(), seat.section(), seat.row(), seat.col(), status.name(), seat.priceMinor());
                })
                .toList();
        return new SeatMapView(showId, now, seats);
    }
}
