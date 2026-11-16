package com.vivek.ticketreservation.inventory.api;

/** Published hold status (schema HoldStatus). Mirrors the internal state machine without exposing it. */
public enum HoldState { HELD, CONFIRMED, RELEASED, EXPIRED }
