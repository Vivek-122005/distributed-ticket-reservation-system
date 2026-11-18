package com.vivek.ticketreservation.payment.application.port;

/** Remembers which provider events were already handled (P4.3). */
public interface WebhookInbox {

    boolean seen(String eventId);

    /** Records the event; a concurrent duplicate that recorded it first is not an error. */
    void record(String eventId, String orderId, String type, String result);
}
