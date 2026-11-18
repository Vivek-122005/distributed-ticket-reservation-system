package com.vivek.ticketreservation.common.idempotency;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.vivek.ticketreservation.common.idempotency.IdempotencyStore.Outcome;
import com.vivek.ticketreservation.common.idempotency.IdempotencyStore.StoredResponse;
import com.vivek.ticketreservation.common.web.ApiError;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Exactly-once semantics for mutating endpoints, as a reusable servlet filter (P3.4–P3.5).
 *
 * <p>For each {@code POST} it guards: claim {@code (user, Idempotency-Key)} in the store, run the
 * request, store the response. A retry with the same key and body gets the stored response replayed
 * byte for byte (header {@code Idempotent-Replayed: true}); the same key with a different body gets
 * {@code 422 IDEMPOTENCY_KEY_REUSED}; a retry while the first is still running gets
 * {@code 409 IDEMPOTENCY_IN_PROGRESS} with {@code Retry-After}. 5xx answers and exceptions are not
 * stored, so the client's retry runs the request again.
 *
 * <p>Pattern: Idempotency Key (docs/lld/DESIGN_PATTERNS.md#12). A filter rather than code in each
 * controller, so every module gets the same behaviour by registering it for its paths (OCP).
 */
public class IdempotencyFilter extends OncePerRequestFilter {
    public static final String KEY_HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";
    private static final int MAX_BODY = 64 * 1024;
    private static final int MAX_KEY = 100;

    private final IdempotencyStore store;
    private final ObjectMapper json;
    private final String userHeader;
    private final Duration ttl;
    private final Duration lease;

    public IdempotencyFilter(IdempotencyStore store, ObjectMapper json, String userHeader, Duration ttl, Duration lease) {
        this.store = store;
        this.json = json;
        this.userHeader = userHeader;
        this.ttl = ttl;
        this.lease = lease;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !"POST".equals(request.getMethod());
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        UUID user = parseUser(request.getHeader(userHeader));
        if (user == null) { chain.doFilter(request, response); return; }   // the controller rejects it as 400
        String key = request.getHeader(KEY_HEADER);
        if (key == null || key.isBlank() || key.length() > MAX_KEY) {
            error(response, 400, "INVALID_REQUEST", "Idempotency-Key header (1-" + MAX_KEY + " characters) is required", false);
            return;
        }

        CachedBodyRequest cached;
        try { cached = new CachedBodyRequest(request, MAX_BODY); }
        catch (IOException tooLarge) { error(response, 400, "INVALID_REQUEST", tooLarge.getMessage(), false); return; }

        switch (store.begin(user, key, fingerprint(request, cached.body()), ttl, lease)) {
            case Outcome.Completed done -> replay(response, done.response());
            case Outcome.InProgress ignored -> {
                response.setHeader(HttpHeaders.RETRY_AFTER, "1");
                error(response, 409, "IDEMPOTENCY_IN_PROGRESS", "A request with this Idempotency-Key is still being processed", true);
            }
            case Outcome.Mismatch ignored ->
                    error(response, 422, "IDEMPOTENCY_KEY_REUSED", "Idempotency-Key was already used with a different request.", false);
            case Outcome.Started ignored -> process(cached, response, chain, user, key);
        }
    }

    private void process(CachedBodyRequest request, HttpServletResponse response, FilterChain chain, UUID user, String key)
            throws ServletException, IOException {
        ContentCachingResponseWrapper captured = new ContentCachingResponseWrapper(response);
        try {
            chain.doFilter(request, captured);
        } catch (ServletException | IOException | RuntimeException e) {
            store.abandon(user, key);
            throw e;
        }
        if (captured.getStatus() >= 500) {
            store.abandon(user, key);
        } else {
            store.complete(user, key, new StoredResponse(captured.getStatus(), captured.getContentType(),
                    captured.getHeader(HttpHeaders.LOCATION), captured.getContentAsByteArray()));
        }
        captured.copyBodyToResponse();
    }

    private static void replay(HttpServletResponse response, StoredResponse stored) throws IOException {
        response.setStatus(stored.status());
        if (stored.contentType() != null) response.setContentType(stored.contentType());
        if (stored.location() != null) response.setHeader(HttpHeaders.LOCATION, stored.location());
        response.setHeader(REPLAYED_HEADER, "true");
        byte[] body = stored.body() == null ? new byte[0] : stored.body();
        response.setContentLength(body.length);
        response.getOutputStream().write(body);
    }

    private void error(HttpServletResponse response, int status, String code, String message, boolean retryable) throws IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.getOutputStream().write(json.writeValueAsBytes(ApiError.of(code, message, retryable)));
    }

    /** SHA-256 of method, path and body: what "the same request" means. */
    static String fingerprint(HttpServletRequest request, byte[] body) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update((request.getMethod() + " " + request.getRequestURI() + "\n").getBytes(StandardCharsets.UTF_8));
            sha.update(body);
            return HexFormat.of().formatHex(sha.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static UUID parseUser(String header) {
        if (header == null) return null;
        try { return UUID.fromString(header); } catch (IllegalArgumentException e) { return null; }
    }
}
