package com.vivek.ticketreservation.support;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.IntFunction;

/**
 * Runs N attempts at the same instant and collects each outcome (a value or the exception thrown).
 *
 * <p>All tasks wait on one start gate, so they hit the database together instead of trickling in as
 * threads are created. Virtual threads keep 2,000 concurrent attempts cheap; the real concurrency
 * at the database is bounded by the connection pool, as in production.
 */
public final class Contention {

    /** Outcome of one attempt: exactly one of {@code value} and {@code error} is set. */
    public record Outcome<T>(T value, Throwable error) {
        public boolean succeeded() { return error == null; }
    }

    private Contention() { }

    public static <T> List<Outcome<T>> race(int attempts, IntFunction<T> attempt) throws InterruptedException {
        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Outcome<T>>> futures = new ArrayList<>(attempts);
        try (ExecutorService pool = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < attempts; i++) {
                int index = i;
                futures.add(pool.submit(() -> {
                    gate.await();
                    try {
                        return new Outcome<>(attempt.apply(index), null);
                    } catch (Throwable error) {
                        return new Outcome<T>(null, error);
                    }
                }));
            }
            gate.countDown();
            pool.shutdown();
            if (!pool.awaitTermination(2, TimeUnit.MINUTES)) throw new IllegalStateException("contention run did not finish");
        }
        List<Outcome<T>> outcomes = new ArrayList<>(attempts);
        for (Future<Outcome<T>> future : futures) {
            try {
                outcomes.add(future.get());
            } catch (java.util.concurrent.ExecutionException e) {
                throw new IllegalStateException(e);
            }
        }
        return outcomes;
    }
}
