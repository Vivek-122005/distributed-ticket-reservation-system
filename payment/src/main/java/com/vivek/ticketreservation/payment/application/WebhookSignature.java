package com.vivek.ticketreservation.payment.application;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.HexFormat;

/**
 * HMAC-SHA256 over the raw webhook body, hex-encoded: the scheme Razorpay and Stripe use. Verification
 * compares in constant time, so the signature can't be guessed byte by byte from response timing.
 */
public final class WebhookSignature {
    private final byte[] secret;

    public WebhookSignature(String secret) {
        if (secret == null || secret.length() < 16) throw new IllegalArgumentException("webhook secret must be at least 16 characters");
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String sign(byte[] body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    public boolean verify(byte[] body, String signature) {
        if (signature == null) return false;
        return MessageDigest.isEqual(sign(body).getBytes(StandardCharsets.UTF_8), signature.getBytes(StandardCharsets.UTF_8));
    }
}
